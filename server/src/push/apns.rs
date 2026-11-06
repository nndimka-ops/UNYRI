//! Native APNs transport. No token, bearer, payload or upstream error body is logged.
use std::collections::{BTreeMap, HashSet};
use std::sync::Arc;

use async_trait::async_trait;
use chrono::{DateTime, Utc};
use jsonwebtoken::{encode, Algorithm, EncodingKey, Header};
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;
use uuid::Uuid;

use super::models::PushEvent;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Environment {
    Production,
    Sandbox,
}

impl Environment {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Production => "production",
            Self::Sandbox => "sandbox",
        }
    }
    fn endpoint(self) -> &'static str {
        match self {
            Self::Production => "https://api.push.apple.com",
            Self::Sandbox => "https://api.sandbox.push.apple.com",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Alert,
    Voip,
}
impl Kind {
    pub fn provider(self) -> &'static str {
        match self {
            Self::Alert => "apns",
            Self::Voip => "apns_voip",
        }
    }
    fn push_type(self) -> &'static str {
        match self {
            Self::Alert => "alert",
            Self::Voip => "voip",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Preferences {
    pub message_alert_enabled: bool,
    pub message_sound_enabled: bool,
    pub permission_allowed: bool,
}

// Deliberately no Debug: a Target contains a device token.
#[derive(Clone)]
pub struct Target {
    pub kind: Kind,
    pub environment: Environment,
    pub topic: String,
    pub token: String,
    pub registration_id: Uuid,
    pub revision: i64,
    pub token_registered_at: DateTime<Utc>,
    pub preferences: Preferences,
}

#[derive(Clone)]
pub struct Context {
    pub push_id: Uuid,
    pub user_id: Uuid,
    pub device_id: Uuid,
    pub sent_at_ms: i64,
    pub expires_at_ms: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Error {
    Configuration,
    Signing,
    Clock,
    Transport,
    Protocol,
    Oversized,
    Payload,
}
impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Static enum names only; never stringify a reqwest/crypto error.
        write!(f, "APNs {:?}", self)
    }
}
impl std::error::Error for Error {}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Outcome {
    Accepted,
    InvalidToken { invalid_since_ms: Option<i64> },
    Retry { minimum_delay_seconds: i64 },
    Permanent,
}

pub fn normalize_token(token: &str) -> Result<String, Error> {
    if token.is_empty()
        || token.len() > 4096
        || token.len() % 2 != 0
        || !token.bytes().all(|byte| byte.is_ascii_hexdigit())
    {
        return Err(Error::Configuration);
    }
    Ok(token.to_ascii_lowercase())
}

pub trait Clock: Send + Sync {
    fn now_seconds(&self) -> i64;
}
pub struct SystemClock;
impl Clock for SystemClock {
    fn now_seconds(&self) -> i64 {
        Utc::now().timestamp()
    }
}

struct CachedJwt {
    issued_at: i64,
    value: String,
}
pub struct JwtProvider {
    team_id: String,
    key_id: String,
    key: EncodingKey,
    clock: Arc<dyn Clock>,
    cached: Mutex<Option<CachedJwt>>,
}

#[derive(Serialize)]
struct Claims<'a> {
    iss: &'a str,
    iat: i64,
}

impl JwtProvider {
    pub fn new(
        team_id: String,
        key_id: String,
        pem: &[u8],
        clock: Arc<dyn Clock>,
    ) -> Result<Self, Error> {
        let valid_id = |id: &str| {
            id.len() == 10
                && id
                    .bytes()
                    .all(|c| c.is_ascii_uppercase() || c.is_ascii_digit())
        };
        if !valid_id(&team_id) || !valid_id(&key_id) {
            return Err(Error::Configuration);
        }
        let key = EncodingKey::from_ec_pem(pem).map_err(|_| Error::Configuration)?;
        Ok(Self {
            team_id,
            key_id,
            key,
            clock,
            cached: Mutex::new(None),
        })
    }

    async fn token(&self) -> Result<String, Error> {
        let mut cached = self.cached.lock().await;
        let now = self.clock.now_seconds();
        if now <= 0 {
            return Err(Error::Clock);
        }
        if let Some(token) = cached.as_ref() {
            let age = now.checked_sub(token.issued_at).ok_or(Error::Clock)?;
            if age < 0 {
                return Err(Error::Clock);
            }
            if age < 40 * 60 {
                return Ok(token.value.clone());
            }
        }
        let mut header = Header::new(Algorithm::ES256);
        header.kid = Some(self.key_id.clone());
        let value = encode(
            &header,
            &Claims {
                iss: &self.team_id,
                iat: now,
            },
            &self.key,
        )
        .map_err(|_| Error::Signing)?;
        *cached = Some(CachedJwt {
            issued_at: now,
            value: value.clone(),
        });
        Ok(value)
    }
}

// Deliberately no Debug: wire requests contain a token-bearing URL and JWT.
pub struct Request {
    pub url: String,
    pub bearer: String,
    pub topic: String,
    pub push_type: &'static str,
    pub push_id: Uuid,
    pub expiration: i64,
    pub collapse_id: Option<String>,
    pub payload: Vec<u8>,
}
pub struct Response {
    pub status: u16,
    pub body: Vec<u8>,
}

#[async_trait]
pub trait Transport: Send + Sync {
    async fn post(&self, request: Request) -> Result<Response, Error>;
}

pub struct Http2Transport {
    client: reqwest::Client,
}
impl Http2Transport {
    pub fn new() -> Result<Self, Error> {
        let client = reqwest::Client::builder()
            .https_only(true)
            .http2_prior_knowledge()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(std::time::Duration::from_secs(5))
            .timeout(std::time::Duration::from_secs(10))
            .build()
            .map_err(|_| Error::Configuration)?;
        Ok(Self { client })
    }
}

#[async_trait]
impl Transport for Http2Transport {
    async fn post(&self, request: Request) -> Result<Response, Error> {
        let mut builder = self
            .client
            .post(request.url)
            .version(reqwest::Version::HTTP_2)
            .header("authorization", format!("bearer {}", request.bearer))
            .header("content-type", "application/json")
            .header("apns-topic", request.topic)
            .header("apns-push-type", request.push_type)
            .header("apns-id", request.push_id.to_string())
            .header("apns-priority", "10")
            .header("apns-expiration", request.expiration.to_string());
        if let Some(collapse) = request.collapse_id {
            builder = builder.header("apns-collapse-id", collapse);
        }
        let mut response = builder
            .body(request.payload)
            .send()
            .await
            .map_err(|_| Error::Transport)?;
        if response.version() != reqwest::Version::HTTP_2 {
            return Err(Error::Protocol);
        }
        let status = response.status().as_u16();
        let mut body = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|_| Error::Transport)? {
            if body.len() + chunk.len() > 8192 {
                return Err(Error::Oversized);
            }
            body.extend_from_slice(&chunk);
        }
        Ok(Response { status, body })
    }
}

pub struct Provider {
    environment: Environment,
    topics: HashSet<String>,
    jwt: JwtProvider,
    transport: Arc<dyn Transport>,
}

impl Provider {
    pub fn environment(&self) -> Environment {
        self.environment
    }
    pub fn new(
        environment: Environment,
        topics: HashSet<String>,
        jwt: JwtProvider,
        transport: Arc<dyn Transport>,
    ) -> Result<Self, Error> {
        if topics.is_empty()
            || topics.iter().any(|topic| {
                topic.is_empty()
                    || topic.len() > 255
                    || !topic
                        .bytes()
                        .all(|c| c.is_ascii_alphanumeric() || c == b'.' || c == b'-')
            })
        {
            return Err(Error::Configuration);
        }
        Ok(Self {
            environment,
            topics,
            jwt,
            transport,
        })
    }

    pub fn allows(&self, kind: Kind, topic: &str) -> bool {
        self.topics.contains(topic) && (topic.ends_with(".voip") == (kind == Kind::Voip))
    }

    pub async fn send(
        &self,
        target: &Target,
        event: &PushEvent,
        context: &Context,
    ) -> Result<Outcome, Error> {
        if target.environment != self.environment
            || !self.allows(target.kind, &target.topic)
            || target.revision <= 0
        {
            return Err(Error::Configuration);
        }
        if context.expires_at_ms <= context.sent_at_ms {
            return Ok(Outcome::Permanent);
        }
        let token = normalize_token(&target.token)?;
        let payload = payload(target, event, context)?;
        let expiration = if target.kind == Kind::Voip {
            0
        } else {
            context.expires_at_ms / 1000
        };
        let collapse_id = if event.event == "new_message" {
            event
                .data
                .get("chat_id")
                .and_then(|id| Uuid::parse_str(id).ok())
                .map(|id| format!("chat:{id}"))
        } else {
            None
        };
        let request = Request {
            url: format!("{}/3/device/{token}", self.environment.endpoint()),
            bearer: self.jwt.token().await?,
            topic: target.topic.clone(),
            push_type: target.kind.push_type(),
            push_id: context.push_id,
            expiration,
            collapse_id,
            payload,
        };
        let response = self.transport.post(request).await?;
        Ok(classify(response.status, &response.body))
    }
}

/// Read only explicitly named optional settings; absent APNs never disables FCM.
/// Private-key bytes are read only by the deployed provider, never by an audit.
pub fn configured_providers() -> Vec<Arc<Provider>> {
    let topics: HashSet<String> = std::env::var("APNS_ALLOWED_TOPICS")
        .unwrap_or_default()
        .split(',')
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .map(str::to_owned)
        .collect();
    let enabled = std::env::var("APNS_ALLOWED_ENVIRONMENTS").unwrap_or_default();
    let mut providers = Vec::new();
    for name in enabled
        .split(',')
        .map(str::trim)
        .filter(|name| !name.is_empty())
    {
        let environment = match name {
            "production" => Environment::Production,
            "sandbox" => Environment::Sandbox,
            _ => {
                tracing::warn!("APNs configuration disabled: unknown environment");
                continue;
            }
        };
        let prefix = format!("APNS_{}", name.to_ascii_uppercase());
        let load = || -> Result<Provider, Error> {
            let team =
                std::env::var(format!("{prefix}_TEAM_ID")).map_err(|_| Error::Configuration)?;
            let id = std::env::var(format!("{prefix}_KEY_ID")).map_err(|_| Error::Configuration)?;
            let path =
                std::env::var(format!("{prefix}_KEY_FILE")).map_err(|_| Error::Configuration)?;
            let pem = std::fs::read(path).map_err(|_| Error::Configuration)?;
            let jwt = JwtProvider::new(team, id, &pem, Arc::new(SystemClock))?;
            Provider::new(
                environment,
                topics.clone(),
                jwt,
                Arc::new(Http2Transport::new()?),
            )
        };
        match load() {
            Ok(provider) => providers.push(Arc::new(provider)),
            Err(_) => {
                tracing::warn!("APNs environment configuration unavailable; provider disabled")
            }
        }
    }
    providers
}

pub fn payload(target: &Target, event: &PushEvent, context: &Context) -> Result<Vec<u8>, Error> {
    let fields: &[&str] = match (target.kind, event.event.as_str()) {
        (Kind::Alert, "new_message") => &["chat_id", "message_id", "sequence"],
        (Kind::Alert, "security_event") => &["security_event_id"],
        (Kind::Alert, "admin_notice") => &["notice_id"],
        (Kind::Voip, "incoming_call") => &["call_id", "media_kind"],
        _ => return Err(Error::Payload),
    };
    let aps = if target.kind == Kind::Alert {
        if !target.preferences.permission_allowed
            || (event.event == "new_message" && !target.preferences.message_alert_enabled)
        {
            return Err(Error::Payload);
        }
        let key = if event.event == "new_message" {
            "New message"
        } else {
            "Notifications"
        };
        let mut aps = serde_json::json!({"alert": {"title": "UNYRI", "loc-key": key}});
        if event.event == "new_message" && target.preferences.message_sound_enabled {
            aps["sound"] = serde_json::json!("default");
        }
        aps
    } else {
        serde_json::json!({})
    };
    let mut object = BTreeMap::from([
        ("aps", aps),
        ("event", serde_json::json!(event.event)),
        ("user_id", serde_json::json!(context.user_id)),
        ("device_id", serde_json::json!(context.device_id)),
        ("push_id", serde_json::json!(context.push_id)),
        ("sent_at_ms", serde_json::json!(context.sent_at_ms)),
        ("expires_at_ms", serde_json::json!(context.expires_at_ms)),
        (
            "registration",
            serde_json::json!({"provider": target.kind.provider(), "id": target.registration_id, "revision": target.revision}),
        ),
    ]);
    for field in fields {
        let value = event.data.get(*field).ok_or(Error::Payload)?;
        if value.len() > 128 {
            return Err(Error::Payload);
        }
        object.insert(field, serde_json::json!(value));
    }
    let bytes = serde_json::to_vec(&object).map_err(|_| Error::Payload)?;
    if bytes.len() > 4096 {
        return Err(Error::Oversized);
    }
    Ok(bytes)
}

pub fn classify(status: u16, body: &[u8]) -> Outcome {
    if status == 200 {
        return Outcome::Accepted;
    }
    let value: serde_json::Value = serde_json::from_slice(body).unwrap_or(serde_json::Value::Null);
    let reason = value.get("reason").and_then(|v| v.as_str()).unwrap_or("");
    if status == 410 && matches!(reason, "Unregistered" | "ExpiredToken") {
        return Outcome::InvalidToken {
            invalid_since_ms: value.get("timestamp").and_then(|v| v.as_i64()),
        };
    }
    if status == 400 && reason == "BadDeviceToken" {
        return Outcome::InvalidToken {
            invalid_since_ms: None,
        };
    }
    if status == 429 {
        return Outcome::Retry {
            minimum_delay_seconds: 60,
        };
    }
    if status >= 500 {
        return Outcome::Retry {
            minimum_delay_seconds: 900,
        };
    }
    Outcome::Permanent
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::{engine::general_purpose::STANDARD, Engine};
    use tokio_rustls::{
        rustls::{
            pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer},
            ServerConfig,
        },
        TlsAcceptor,
    };

    #[tokio::test]
    async fn real_transport_negotiates_tls_h2_and_sends_apns_headers_without_http1_downgrade() {
        // Disposable test-only certificate/key. Never an Apple/APNs/signing key.
        let directory = std::env::temp_dir().join(format!("unyri-apns-h2-test-{}", Uuid::new_v4()));
        std::fs::create_dir(&directory).unwrap();
        let cert_path = directory.join("test-cert.pem");
        let key_path = directory.join("test-key.pem");
        let status = std::process::Command::new("openssl")
            .args([
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-sha256",
                "-nodes",
                "-days",
                "2",
                "-subj",
                "/CN=localhost",
                "-addext",
                "subjectAltName=DNS:localhost,IP:127.0.0.1",
                "-addext",
                "basicConstraints=critical,CA:FALSE",
                "-addext",
                "extendedKeyUsage=serverAuth",
                "-keyout",
            ])
            .arg(&key_path)
            .arg("-out")
            .arg(&cert_path)
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .unwrap();
        assert!(status.success());
        let der = |path: &std::path::Path| {
            let text = std::fs::read_to_string(path).unwrap();
            STANDARD
                .decode(
                    text.lines()
                        .filter(|line| !line.starts_with("-----"))
                        .collect::<String>(),
                )
                .unwrap()
        };
        let certificate = der(&cert_path);
        let key = der(&key_path);
        let mut config = ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(
                vec![CertificateDer::from(certificate.clone())],
                PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key)),
            )
            .unwrap();
        config.alpn_protocols = vec![b"h2".to_vec()];
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let (tx, rx) = tokio::sync::oneshot::channel();
        let task = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.unwrap();
            let tls = TlsAcceptor::from(Arc::new(config))
                .accept(tcp)
                .await
                .unwrap();
            assert_eq!(tls.get_ref().1.alpn_protocol(), Some(b"h2".as_slice()));
            let mut connection = h2::server::handshake(tls).await.unwrap();
            let mut tx = Some(tx);
            while let Some(result) = connection.accept().await {
                let (request, mut respond) = result.unwrap();
                let (parts, mut body) = request.into_parts();
                let mut data = Vec::new();
                while let Some(chunk) = body.data().await {
                    data.extend_from_slice(&chunk.unwrap());
                }
                if let Some(tx) = tx.take() {
                    tx.send((
                        parts.version,
                        parts.uri.path().to_owned(),
                        parts.headers,
                        data,
                    ))
                    .unwrap();
                }
                respond
                    .send_response(
                        axum::http::Response::builder()
                            .status(200)
                            .body(())
                            .unwrap(),
                        true,
                    )
                    .unwrap();
            }
        });
        let client = reqwest::Client::builder()
            .https_only(true)
            .http2_prior_knowledge()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(std::time::Duration::from_secs(5))
            .add_root_certificate(reqwest::Certificate::from_der(&certificate).unwrap())
            .build()
            .unwrap();
        let transport = Http2Transport { client };
        let push_id = Uuid::new_v4();
        let response = transport
            .post(Request {
                url: format!("https://localhost:{}/3/device/aabb", address.port()),
                bearer: "synthetic-test-bearer".into(),
                topic: "com.example.synthetic.voip".into(),
                push_type: "voip",
                push_id,
                expiration: 0,
                collapse_id: None,
                payload: b"{}".to_vec(),
            })
            .await
            .unwrap();
        assert_eq!(response.status, 200);
        let (version, path, headers, body) = rx.await.unwrap();
        assert_eq!(version, reqwest::Version::HTTP_2);
        assert_eq!(path, "/3/device/aabb");
        assert_eq!(headers["apns-push-type"], "voip");
        assert_eq!(headers["apns-priority"], "10");
        assert_eq!(headers["apns-expiration"], "0");
        assert_eq!(headers["apns-id"], push_id.to_string());
        assert_eq!(headers["authorization"], "bearer synthetic-test-bearer");
        assert_eq!(body, b"{}");
        task.abort();
        let _ = task.await;
        // Trust failure and HTTP/1-only ALPN must both fail closed.
        for (trust_test_certificate, alpn) in
            [(false, b"h2".to_vec()), (true, b"http/1.1".to_vec())]
        {
            let mut config = ServerConfig::builder()
                .with_no_client_auth()
                .with_single_cert(
                    vec![CertificateDer::from(certificate.clone())],
                    PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(der(&key_path))),
                )
                .unwrap();
            config.alpn_protocols = vec![alpn];
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let address = listener.local_addr().unwrap();
            let rejected = tokio::spawn(async move {
                let (tcp, _) = listener.accept().await.unwrap();
                TlsAcceptor::from(Arc::new(config))
                    .accept(tcp)
                    .await
                    .is_err()
            });
            let mut builder = reqwest::Client::builder()
                .https_only(true)
                .http2_prior_knowledge()
                .redirect(reqwest::redirect::Policy::none())
                .timeout(std::time::Duration::from_secs(5));
            if trust_test_certificate {
                builder = builder
                    .add_root_certificate(reqwest::Certificate::from_der(&certificate).unwrap());
            }
            let transport = Http2Transport {
                client: builder.build().unwrap(),
            };
            let result = transport
                .post(Request {
                    url: format!("https://localhost:{}/3/device/aabb", address.port()),
                    bearer: "synthetic-test-bearer".into(),
                    topic: "com.example.synthetic.voip".into(),
                    push_type: "voip",
                    push_id,
                    expiration: 0,
                    collapse_id: None,
                    payload: b"{}".to_vec(),
                })
                .await;
            assert!(matches!(
                result,
                Err(Error::Transport) | Err(Error::Protocol)
            ));
            assert!(rejected.await.unwrap());
        }
        std::fs::remove_file(cert_path).unwrap();
        std::fs::remove_file(key_path).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }
}
