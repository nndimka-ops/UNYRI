use std::path::Path;
use std::sync::Arc;
use std::time::{Duration, Instant};

use async_trait::async_trait;
use jsonwebtoken::{encode, Algorithm, EncodingKey, Header};
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::push::models::{PushError, PushEvent, PushPriority, PushSendOutcome};

/// Transport abstraction so FCM HTTP behaviour (endpoint, bearer header, body)
/// is unit-testable without any Google network access.
#[async_trait]
pub trait FcmTransport: Send + Sync {
    async fn post(
        &self,
        url: &str,
        bearer: &str,
        content_type: &str,
        body: &str,
    ) -> Result<TransportResponse, String>;
}

pub struct TransportResponse {
    pub status: u16,
    pub body: String,
}

/// Real HTTPS transport over reqwest.
pub struct ReqwestFcmTransport {
    client: reqwest::Client,
}

impl ReqwestFcmTransport {
    pub fn new(client: reqwest::Client) -> Self {
        Self { client }
    }
}

#[async_trait]
impl FcmTransport for ReqwestFcmTransport {
    async fn post(
        &self,
        url: &str,
        bearer: &str,
        content_type: &str,
        body: &str,
    ) -> Result<TransportResponse, String> {
        let mut request = self
            .client
            .post(url)
            .header("Content-Type", content_type)
            .body(body.to_string());
        if !bearer.is_empty() {
            request = request.header("Authorization", format!("Bearer {bearer}"));
        }
        let response = request.send().await.map_err(|err| err.to_string())?;
        let status = response.status().as_u16();
        let body = response.text().await.unwrap_or_default();
        Ok(TransportResponse { status, body })
    }
}

/// Short-lived Google OAuth access token (never logged in full).
#[derive(Clone, Debug)]
pub struct AccessToken {
    pub value: String,
    pub expires_at: Instant,
}

/// Source of OAuth access tokens. Implementations fetch a fresh token; the
/// [FcmAccessTokenProvider] on top adds caching + single-flight refresh.
#[async_trait]
pub trait AccessTokenSource: Send + Sync {
    async fn fetch_token(&self) -> Result<AccessToken, PushError>;
}

/// Service-account OAuth token exchange (`urn:ietf:params:oauth:grant-type:
/// jwt-bearer`). The cryptographic signing is delegated entirely to the
/// `jsonwebtoken` crate (RS256) - nothing hand-rolled here.
pub struct ServiceAccountTokenSource {
    client_email: String,
    token_uri: String,
    private_key_pem: String,
    transport: Arc<dyn FcmTransport>,
}

/// Minimal subset of the service-account JSON we actually use.
#[derive(Deserialize)]
struct ServiceAccountFile {
    client_email: String,
    token_uri: String,
    private_key: String,
}

impl ServiceAccountTokenSource {
    /// Loads a service-account JSON file. Never includes file contents in
    /// errors (credentials must not leak through error formatting).
    pub fn from_file(path: &Path, transport: Arc<dyn FcmTransport>) -> Result<Self, PushError> {
        let text = std::fs::read_to_string(path)
            .map_err(|_| PushError::Invalid("service account file is not readable".into()))?;
        Self::from_json(&text, transport)
    }

    pub fn from_json(text: &str, transport: Arc<dyn FcmTransport>) -> Result<Self, PushError> {
        let account: ServiceAccountFile = serde_json::from_str(text)
            .map_err(|_| PushError::Invalid("service account JSON is malformed".into()))?;
        if account.private_key.is_empty() {
            return Err(PushError::Invalid(
                "service account JSON has no private_key".into(),
            ));
        }
        Ok(Self {
            client_email: account.client_email,
            token_uri: account.token_uri,
            private_key_pem: account.private_key,
            transport,
        })
    }
}

#[derive(Serialize)]
struct OAuthJwtClaims {
    iss: String,
    scope: String,
    aud: String,
    iat: i64,
    exp: i64,
}

#[derive(Deserialize)]
struct OAuthTokenResponse {
    access_token: String,
    expires_in: Option<u64>,
}

#[async_trait]
impl AccessTokenSource for ServiceAccountTokenSource {
    async fn fetch_token(&self) -> Result<AccessToken, PushError> {
        let now = chrono::Utc::now().timestamp();
        let claims = OAuthJwtClaims {
            iss: self.client_email.clone(),
            scope: "https://www.googleapis.com/auth/firebase.messaging".into(),
            aud: self.token_uri.clone(),
            iat: now,
            // Google recommends ~1h assertion lifetime.
            exp: now + 3600,
        };
        let encoding_key =
            EncodingKey::from_rsa_pem(self.private_key_pem.as_bytes()).map_err(|_| {
                PushError::Invalid("service account private key is not valid RSA PEM".into())
            })?;
        let mut header = Header::new(Algorithm::RS256);
        header.typ = Some("JWT".into());
        let assertion = encode(&header, &claims, &encoding_key)
            .map_err(|err| PushError::Auth(err.to_string()))?;

        let body = format!(
            "grant_type={}&assertion={}",
            url_encode("urn:ietf:params:oauth:grant-type:jwt-bearer"),
            url_encode(&assertion)
        );
        let response = self
            .transport
            .post(
                &self.token_uri,
                "",
                "application/x-www-form-urlencoded",
                &body,
            )
            .await
            .map_err(PushError::Auth)?;
        if response.status != 200 {
            // The Google error body is safe to include (JSON error code /
            // description - never a token or private key).
            let detail = response.body.chars().take(300).collect::<String>();
            return Err(PushError::Auth(format!(
                "token endpoint returned {}: {detail}",
                response.status
            )));
        }
        let parsed: OAuthTokenResponse = serde_json::from_str(&response.body)
            .map_err(|_| PushError::Auth("token response malformed".into()))?;
        let ttl = parsed.expires_in.unwrap_or(3600);
        Ok(AccessToken {
            value: parsed.access_token,
            expires_at: Instant::now() + Duration::from_secs(ttl),
        })
    }
}

fn url_encode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(byte as char)
            }
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

/// Caches a short-lived OAuth token and guarantees a single in-flight refresh
/// for any number of concurrent senders (the mutex serializes the check-then-
/// fetch critical section; FCM sends are low-rate so this is fine).
pub struct FcmAccessTokenProvider {
    source: Arc<dyn AccessTokenSource>,
    cached: Mutex<Option<AccessToken>>,
}

impl FcmAccessTokenProvider {
    pub fn new(source: Arc<dyn AccessTokenSource>) -> Self {
        Self {
            source,
            cached: Mutex::new(None),
        }
    }

    pub async fn get_token(&self) -> Result<AccessToken, PushError> {
        let mut guard = self.cached.lock().await;
        if let Some(token) = guard.as_ref() {
            // Refresh 30s before actual expiry to avoid using a token that
            // dies mid-request.
            if token.expires_at.saturating_duration_since(Instant::now()) > Duration::from_secs(30)
            {
                return Ok(token.clone());
            }
        }
        let fresh = self.source.fetch_token().await?;
        *guard = Some(fresh.clone());
        Ok(fresh)
    }
}

/// A [crate::push::models::PushProvider] backed by FCM HTTP v1.
pub struct FcmPushProvider {
    project_id: String,
    tokens: FcmAccessTokenProvider,
    transport: Arc<dyn FcmTransport>,
}

impl FcmPushProvider {
    pub fn new(
        project_id: String,
        tokens: FcmAccessTokenProvider,
        transport: Arc<dyn FcmTransport>,
    ) -> Self {
        Self {
            project_id,
            tokens,
            transport,
        }
    }

    /// Builds the FCM HTTP v1 send URL - exposed for the endpoint unit test.
    pub fn send_url(project_id: &str) -> String {
        format!("https://fcm.googleapis.com/v1/projects/{project_id}/messages:send")
    }

    pub fn build_message(&self, fcm_token: &str, event: &PushEvent) -> serde_json::Value {
        let priority = match event.priority {
            PushPriority::High => "high",
            PushPriority::Normal => "normal",
        };
        let mut android = serde_json::json!({
            "priority": priority,
            "ttl": format!("{}s", event.ttl_seconds),
        });
        if let Some(collapse_key) = &event.collapse_key {
            android["collapse_key"] = serde_json::Value::String(collapse_key.clone());
        }
        let data: serde_json::Map<String, serde_json::Value> = event
            .data
            .iter()
            .map(|(k, v)| (k.clone(), serde_json::Value::String(v.clone())))
            .chain(std::iter::once((
                "event".to_string(),
                serde_json::Value::String(event.event.clone()),
            )))
            .collect();
        let mut message = serde_json::json!({
            "token": fcm_token,
            "android": android,
            "data": data,
        });
        if event.event == "admin_notice" {
            let title = event
                .data
                .get("title")
                .cloned()
                .unwrap_or_else(|| "UNYRI".into());
            let body = event.data.get("body").cloned().unwrap_or_default();
            message["notification"] = serde_json::json!({"title": title, "body": body});
            message["apns"] = serde_json::json!({
                "headers": {"apns-priority": "10"},
                "payload": {"aps": {"sound": "default", "category": "COCO_ADMIN_NOTICE"}}
            });
        }
        serde_json::json!({"message": message})
    }

    pub fn classify(status: u16, body: &str) -> PushSendOutcome {
        if status == 200 {
            return PushSendOutcome::Delivered;
        }
        // FCM v1 reports an invalid/expired registration token as HTTP 404
        // with an UNREGISTERED error code.
        if status == 404 && body.contains("UNREGISTERED") {
            return PushSendOutcome::InvalidToken;
        }
        if status == 429 || status >= 500 {
            return PushSendOutcome::Transient;
        }
        PushSendOutcome::Permanent
    }
}

#[async_trait]
impl crate::push::models::PushProvider for FcmPushProvider {
    async fn send(&self, token: &str, event: &PushEvent) -> Result<PushSendOutcome, PushError> {
        let access = self.tokens.get_token().await?;
        let url = Self::send_url(&self.project_id);
        let body = self.build_message(token, event);
        let body_string =
            serde_json::to_string(&body).map_err(|e| PushError::Serialization(e.to_string()))?;
        let response = self
            .transport
            .post(&url, &access.value, "application/json", &body_string)
            .await
            .map_err(|e| PushError::Invalid(format!("transport error: {e}")))?;
        Ok(Self::classify(response.status, &response.body))
    }
}
