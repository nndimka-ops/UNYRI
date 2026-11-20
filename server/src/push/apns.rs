//! Native APNs transport. No token, bearer, payload or upstream error body is logged.
use std::collections::{BTreeMap, HashSet};
use std::sync::Arc;

use async_trait::async_trait;
use chrono::{DateTime, Utc};
use jsonwebtoken::{encode, Algorithm, EncodingKey, Header};
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;
use uuid::Uuid;

use super::models::{Presentation, PushEvent};

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
}

/// The APNs request headers that depend on what is sent.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Headers {
    pub push_type: &'static str,
    pub priority: u8,
    pub collapse_id: Option<String>,
}

pub fn headers(kind: Kind, event: &PushEvent) -> Headers {
    let chat = if event.event == "new_message" {
        event.data.get("chat_id").and_then(|id| Uuid::parse_str(id).ok())
    } else {
        None
    };
    match (kind, event.presentation) {
        (Kind::Voip, _) => Headers { push_type: "voip", priority: 10, collapse_id: None },
        // Apple requires priority 5 for background pushes. A collapse id of its
        // own keeps a wake from touching the chat's visible notification.
        (Kind::Alert, Presentation::Background) => Headers {
            push_type: "background",
            priority: 5,
            collapse_id: chat.map(|id| format!("wake:chat:{id}")),
        },
        // Quiet album items share the chat's collapse id, so they update its one
        // notification instead of stacking new ones.
        (Kind::Alert, Presentation::Alert | Presentation::Quiet) => Headers {
            push_type: "alert",
            priority: 10,
            collapse_id: chat.map(|id| format!("chat:{id}")),
        },
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
    /// The request payload exceeds Apple's 4 KB limit.
    Oversized,
    /// Apple's response body exceeded our read bound.
    ResponseTooLarge,
    Payload,
}
impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Static enum names only; never stringify a reqwest/crypto error.
        write!(f, "APNs {:?}", self)
    }
}
impl std::error::Error for Error {}

impl Error {
    /// Stable code stored in push_outbox and push_tokens.last_error_code.
    pub fn code(self) -> &'static str {
        match self {
            Self::Configuration => "apns_configuration",
            Self::Signing => "apns_signing",
            Self::Clock => "apns_clock",
            Self::Transport => "apns_transport",
            Self::Protocol => "apns_protocol",
            Self::Oversized => "apns_payload_oversized",
            Self::ResponseTooLarge => "apns_response_too_large",
            Self::Payload => "apns_payload",
        }
    }

    /// Payload, request size and configuration errors repeat identically on
    /// every attempt, so retrying them only delays the final outcome.
    pub fn is_retryable(self) -> bool {
        !matches!(self, Self::Configuration | Self::Oversized | Self::Payload)
    }

    /// Failures of this server's own key material or clock: every push fails
    /// until an operator acts.
    pub fn needs_operator(self) -> bool {
        matches!(self, Self::Configuration | Self::Signing | Self::Clock)
    }
}

macro_rules! apns_reasons {
    ($($name:ident),* $(,)?) => {
        /// Apple's fixed list of `reason` strings. Anything else, including a
        /// missing or unparsable body, is `Other`, so no upstream text is ever
        /// stored or logged.
        #[derive(Clone, Copy, Debug, PartialEq, Eq)]
        pub enum Reason { $($name,)* Other }
        impl Reason {
            pub fn parse(value: &str) -> Self {
                match value { $(stringify!($name) => Self::$name,)* _ => Self::Other }
            }
            pub fn as_str(self) -> &'static str {
                match self { $(Self::$name => stringify!($name),)* Self::Other => "Other" }
            }
        }
    };
}
apns_reasons!(
    // 400
    BadCollapseId, BadDeviceToken, BadExpirationDate, BadMessageId, BadPriority, BadTopic,
    DeviceTokenNotForTopic, DuplicateHeaders, IdleTimeout, InvalidPushType, MissingDeviceToken,
    MissingTopic, PayloadEmpty, TopicDisallowed,
    // 403
    BadCertificate, BadCertificateEnvironment, ExpiredProviderToken, Forbidden,
    InvalidProviderToken, MissingProviderToken, UnrelatedKeyIdInToken, BadEnvironmentKeyInToken,
    // 404, 405, 410, 413
    BadPath, MethodNotAllowed, ExpiredToken, Unregistered, PayloadTooLarge,
    // 429
    TooManyProviderTokenUpdates, TooManyRequests,
    // 500, 503
    InternalServerError, ServiceUnavailable, Shutdown,
);

/// What APNs answered when it did not accept a push.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Rejection {
    pub status: u16,
    pub reason: Reason,
}

impl Rejection {
    /// For example `apns_403_InvalidProviderToken`.
    pub fn code(self) -> String {
        format!("apns_{}_{}", self.status, self.reason.as_str())
    }

    /// Apple refused our provider token (key, key id, team or environment).
    /// No device is at fault and every push fails until an operator acts.
    pub fn is_provider_auth(self) -> bool {
        self.status == 403
            && matches!(
                self.reason,
                Reason::InvalidProviderToken
                    | Reason::ExpiredProviderToken
                    | Reason::MissingProviderToken
                    | Reason::UnrelatedKeyIdInToken
                    | Reason::BadEnvironmentKeyInToken
            )
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Outcome {
    Accepted,
    /// The device token is dead; the registration must be retired.
    InvalidToken { invalid_since_ms: Option<i64>, rejection: Rejection },
    /// Worth another attempt, no sooner than the minimum delay.
    Retry { minimum_delay_seconds: i64, rejection: Rejection },
    /// Deterministic for this push; another attempt gets the same answer.
    Permanent { rejection: Rejection },
    /// The push expired before it could be sent.
    Expired,
}

/// Apple 5xx: retry soon; the outbox jitter spreads this to 30-45 s.
const SERVER_ERROR_RETRY_SECONDS: i64 = 30;
const RATE_LIMIT_RETRY_SECONDS: i64 = 60;
/// A refused provider token needs an operator; retrying faster only adds load.
const PROVIDER_AUTH_RETRY_SECONDS: i64 = 300;

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
    /// 10 sends at once; 5 lets the device batch delivery (required for background).
    pub priority: u8,
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
            .header("apns-priority", request.priority.to_string())
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
                return Err(Error::ResponseTooLarge);
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
            return Ok(Outcome::Expired);
        }
        let token = normalize_token(&target.token)?;
        let payload = payload(target, event, context)?;
        let expiration = if target.kind == Kind::Voip {
            0
        } else {
            context.expires_at_ms / 1000
        };
        let Headers { push_type, priority, collapse_id } = headers(target.kind, event);
        let request = Request {
            url: format!("{}/3/device/{token}", self.environment.endpoint()),
            bearer: self.jwt.token().await?,
            topic: target.topic.clone(),
            push_type,
            priority,
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
    if target.kind == Kind::Alert && event.event == "session_check" && event.presentation == Presentation::Background {
        return serde_json::to_vec(&serde_json::json!({"aps":{"content-available":1},"event":"session_check"}))
            .map_err(|_| Error::Payload);
    }
    let fields: &[&str] = match (target.kind, event.event.as_str()) {
        (Kind::Alert, "new_message") => &["chat_id", "message_id", "sequence"],
        (Kind::Alert, "security_event") => &["security_event_id"],
        (Kind::Alert, "admin_notice") => &["notice_id"],
        (Kind::Voip, "incoming_call") => &["call_id", "media_kind"],
        _ => return Err(Error::Payload),
    };
    let message = target.kind == Kind::Alert && event.event == "new_message";
    // Quiet and background exist only for message alerts.
    if !event.presentation.is_alert() && !message {
        return Err(Error::Payload);
    }
    let aps = match (target.kind, event.presentation) {
        (Kind::Voip, _) => serde_json::json!({}),
        // Nothing is shown, so neither notification permission nor the alert
        // setting applies. Apple allows only content-available in a background
        // push; the app syncs, which stores the message and acknowledges it.
        (Kind::Alert, Presentation::Background) => serde_json::json!({"content-available": 1}),
        (Kind::Alert, presentation) => {
            if !target.preferences.permission_allowed
                || (event.event == "new_message" && !target.preferences.message_alert_enabled)
            {
                return Err(Error::Payload);
            }
            let key = if message { "New message" } else { "Notifications" };
            let mut aps = serde_json::json!({"alert": {"title": "UNYRI", "loc-key": key}});
            if message && presentation == Presentation::Alert && target.preferences.message_sound_enabled {
                // The app bundles its own message chime; clients without the file fall
                // back to the default sound. Calls keep their ringtone (VoIP path).
                aps["sound"] = serde_json::json!("unyri_message.caf");
            }
            if presentation == Presentation::Quiet {
                // A later album item: listed without sound or lighting the screen.
                aps["interruption-level"] = serde_json::json!("passive");
            }
            if message {
                // Wakes a suspended app briefly so it syncs and acknowledges delivery
                // (sender's second tick) while the phone stays locked.
                aps["content-available"] = serde_json::json!(1);
                // Runs the notification service extension even when the app was closed;
                // it reports delivery with the token below.
                aps["mutable-content"] = serde_json::json!(1);
            }
            aps
        }
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
    // Only the notification service extension reads the delivery token, and it
    // never runs for a background push (the app acknowledges through sync).
    if message && event.presentation != Presentation::Background {
        if let Some(message_id) = event.data.get("message_id").and_then(|value| Uuid::parse_str(value).ok()) {
            let expires_at = context.sent_at_ms / 1000 + super::delivery::TOKEN_TTL_SECONDS;
            if let Some(token) = super::delivery::token(context.device_id, message_id, expires_at) {
                let mut delivery = serde_json::json!({"token": token, "expires_at": expires_at});
                if let Some(origin) = super::delivery::public_origin() { delivery["origin"] = serde_json::json!(origin); }
                object.insert("delivery", delivery);
            }
        }
    }
    let bytes = serde_json::to_vec(&object).map_err(|_| Error::Payload)?;
    if bytes.len() > 4096 {
        return Err(Error::Oversized);
    }
    Ok(bytes)
}

/// Maps an APNs response to what the outbox does next. The status and Apple's
/// reason travel with every non-accepted outcome so they can be logged and
/// stored as a code.
pub fn classify(status: u16, body: &[u8]) -> Outcome {
    if status == 200 {
        return Outcome::Accepted;
    }
    let value: serde_json::Value = serde_json::from_slice(body).unwrap_or(serde_json::Value::Null);
    let reason = Reason::parse(value.get("reason").and_then(|v| v.as_str()).unwrap_or(""));
    let rejection = Rejection { status, reason };
    match (status, reason) {
        (410, Reason::Unregistered | Reason::ExpiredToken) => Outcome::InvalidToken {
            invalid_since_ms: value.get("timestamp").and_then(|v| v.as_i64()),
            rejection,
        },
        (400, Reason::BadDeviceToken) => Outcome::InvalidToken {
            invalid_since_ms: None,
            rejection,
        },
        // Our credentials, not the device: keep the push and retry later.
        _ if rejection.is_provider_auth() => Outcome::Retry {
            minimum_delay_seconds: PROVIDER_AUTH_RETRY_SECONDS,
            rejection,
        },
        // A dropped idle connection says nothing about this push.
        (400, Reason::IdleTimeout) => Outcome::Retry {
            minimum_delay_seconds: 0,
            rejection,
        },
        (429, _) => Outcome::Retry {
            minimum_delay_seconds: RATE_LIMIT_RETRY_SECONDS,
            rejection,
        },
        (500..=599, _) => Outcome::Retry {
            minimum_delay_seconds: SERVER_ERROR_RETRY_SECONDS,
            rejection,
        },
        _ => Outcome::Permanent { rejection },
    }
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

    fn rejection(status: u16, reason: Reason) -> Rejection {
        Rejection { status, reason }
    }

    #[test]
    fn classify_keeps_status_and_apple_reason() {
        assert_eq!(classify(200, b""), Outcome::Accepted);
        assert_eq!(
            classify(410, br#"{"reason":"Unregistered","timestamp":1234}"#),
            Outcome::InvalidToken { invalid_since_ms: Some(1234), rejection: rejection(410, Reason::Unregistered) }
        );
        assert_eq!(
            classify(400, br#"{"reason":"BadDeviceToken"}"#),
            Outcome::InvalidToken { invalid_since_ms: None, rejection: rejection(400, Reason::BadDeviceToken) }
        );
        for reason in ["InvalidProviderToken", "ExpiredProviderToken", "MissingProviderToken"] {
            let outcome = classify(403, format!(r#"{{"reason":"{reason}"}}"#).as_bytes());
            let Outcome::Retry { minimum_delay_seconds, rejection } = outcome else {
                panic!("provider token rejection must be retried: {reason}");
            };
            assert_eq!(minimum_delay_seconds, PROVIDER_AUTH_RETRY_SECONDS);
            assert!(rejection.is_provider_auth());
            assert_eq!(rejection.code(), format!("apns_403_{reason}"));
        }
        assert_eq!(
            classify(503, br#"{"reason":"ServiceUnavailable"}"#),
            Outcome::Retry { minimum_delay_seconds: 30, rejection: rejection(503, Reason::ServiceUnavailable) }
        );
        assert_eq!(
            classify(429, br#"{"reason":"TooManyRequests"}"#),
            Outcome::Retry { minimum_delay_seconds: 60, rejection: rejection(429, Reason::TooManyRequests) }
        );
        assert_eq!(
            classify(400, br#"{"reason":"IdleTimeout"}"#),
            Outcome::Retry { minimum_delay_seconds: 0, rejection: rejection(400, Reason::IdleTimeout) }
        );
        assert_eq!(
            classify(400, br#"{"reason":"DeviceTokenNotForTopic"}"#),
            Outcome::Permanent { rejection: rejection(400, Reason::DeviceTokenNotForTopic) }
        );
        assert_eq!(
            classify(413, br#"{"reason":"PayloadTooLarge"}"#),
            Outcome::Permanent { rejection: rejection(413, Reason::PayloadTooLarge) }
        );
        // A 403 that is not about our provider token stays final.
        assert_eq!(
            classify(403, br#"{"reason":"Forbidden"}"#),
            Outcome::Permanent { rejection: rejection(403, Reason::Forbidden) }
        );
    }

    #[test]
    fn unknown_or_hostile_reasons_never_reach_codes() {
        let bodies: [&[u8]; 4] = [b"", b"not json", br#"{"reason":"Unregistered\nINJECTED"}"#, br#"{"reason":42}"#];
        for body in bodies {
            let Outcome::Permanent { rejection } = classify(400, body) else {
                panic!("an unknown 400 is final");
            };
            assert_eq!(rejection.reason, Reason::Other);
            assert_eq!(rejection.code(), "apns_400_Other");
        }
        assert_eq!(Reason::parse("TopicDisallowed").as_str(), "TopicDisallowed");
        assert_eq!(Reason::parse("topicdisallowed"), Reason::Other);
    }

    #[test]
    fn deterministic_errors_end_and_server_side_errors_retry() {
        for error in [Error::Payload, Error::Oversized, Error::Configuration] {
            assert!(!error.is_retryable(), "{error}");
        }
        for error in [Error::Signing, Error::Clock, Error::Transport, Error::Protocol, Error::ResponseTooLarge] {
            assert!(error.is_retryable(), "{error}");
        }
        assert_ne!(Error::Oversized.code(), Error::ResponseTooLarge.code());
        assert_eq!(Error::Payload.code(), "apns_payload");
    }

    fn alert_target() -> Target {
        Target {
            kind: Kind::Alert,
            environment: Environment::Production,
            topic: "com.example.synthetic".into(),
            token: "aabbccdd".into(),
            registration_id: Uuid::new_v4(),
            revision: 3,
            token_registered_at: Utc::now(),
            preferences: Preferences { message_alert_enabled: true, message_sound_enabled: true, permission_allowed: true },
        }
    }

    fn message_context() -> Context {
        let now = Utc::now().timestamp_millis();
        Context { push_id: Uuid::new_v4(), user_id: Uuid::new_v4(), device_id: Uuid::new_v4(), sent_at_ms: now, expires_at_ms: now + 60_000 }
    }

    fn built(target: &Target, event: &PushEvent, context: &Context) -> serde_json::Value {
        serde_json::from_slice(&payload(target, event, context).unwrap()).unwrap()
    }

    #[test]
    fn session_check_is_a_content_free_background_hint_even_with_alerts_denied() {
        let mut target=alert_target();
        target.preferences=Preferences { message_alert_enabled:false,message_sound_enabled:false,permission_allowed:false };
        let event=PushEvent::session_check();
        assert_eq!(built(&target,&event,&message_context()),serde_json::json!({"aps":{"content-available":1},"event":"session_check"}));
        let headers=headers(Kind::Alert,&event);
        assert_eq!(headers.push_type,"background");assert_eq!(headers.priority,5);
    }

    #[test]
    fn quiet_album_item_is_a_silent_passive_alert_the_extension_still_acknowledges() {
        crate::push::delivery::init("unit-test-secret-for-quiet-alerts");
        let (chat, message) = (Uuid::new_v4(), Uuid::new_v4());
        let event = PushEvent::new_message(chat, message, 9).with_presentation(Presentation::Quiet);
        let (target, context) = (alert_target(), message_context());
        let value = built(&target, &event, &context);
        let aps = &value["aps"];
        // Sound is on for this device, yet only the album's first item rings.
        assert!(aps.get("sound").is_none());
        assert_eq!(aps["interruption-level"], "passive");
        assert_eq!(aps["alert"]["loc-key"], "New message");
        assert_eq!(aps["mutable-content"], 1);
        assert_eq!(aps["content-available"], 1);
        assert_eq!(value["message_id"], message.to_string());
        assert_eq!(value["chat_id"], chat.to_string());
        assert!(value["delivery"]["token"].as_str().is_some_and(|token| (16..=128).contains(&token.len())));
        assert_eq!(
            headers(Kind::Alert, &event),
            Headers { push_type: "alert", priority: 10, collapse_id: Some(format!("chat:{chat}")) }
        );
        // The audible alert is unchanged and shares the collapse id.
        let audible = PushEvent::new_message(chat, message, 9);
        let value = built(&target, &audible, &context);
        assert_eq!(value["aps"]["sound"], "unyri_message.caf");
        assert!(value["aps"].get("interruption-level").is_none());
        assert_eq!(headers(Kind::Alert, &audible), headers(Kind::Alert, &event));
        // Alerts off: delivery turns the row into a background wake first; the
        // builder never shows a quiet alert the device declined.
        let mut off = alert_target();
        off.preferences.message_alert_enabled = false;
        assert_eq!(payload(&off, &event, &context).unwrap_err(), Error::Payload);
    }

    #[test]
    fn background_wake_carries_only_content_available_and_the_routing_ids() {
        let (chat, message) = (Uuid::new_v4(), Uuid::new_v4());
        let event = PushEvent::new_message(chat, message, 11).with_presentation(Presentation::Background);
        let context = message_context();
        // Muted, alerts off or permission denied: none of them blocks a wake.
        let mut target = alert_target();
        target.preferences = Preferences { message_alert_enabled: false, message_sound_enabled: true, permission_allowed: false };
        let value = built(&target, &event, &context);
        assert_eq!(value["aps"], serde_json::json!({"content-available": 1}));
        let mut keys: Vec<&str> = value.as_object().unwrap().keys().map(String::as_str).collect();
        keys.sort_unstable();
        // Exactly what the iOS strict parser (UnyriPushPayload) requires for a
        // new_message wake; no delivery token, the extension does not run.
        assert_eq!(keys, [
            "aps", "chat_id", "device_id", "event", "expires_at_ms", "message_id", "push_id",
            "registration", "sent_at_ms", "sequence", "user_id",
        ]);
        assert_eq!(value["event"], "new_message");
        assert_eq!(value["message_id"], message.to_string());
        assert_eq!(value["device_id"], context.device_id.to_string());
        assert_eq!(value["registration"]["provider"], "apns");
        assert_eq!(value["registration"]["revision"], 3);
        assert_eq!(value["registration"].as_object().unwrap().len(), 3);
        assert_eq!(
            headers(Kind::Alert, &event),
            Headers { push_type: "background", priority: 5, collapse_id: Some(format!("wake:chat:{chat}")) }
        );
    }

    #[test]
    fn quiet_and_background_exist_only_for_message_alerts() {
        let context = message_context();
        let target = alert_target();
        let notice = PushEvent::admin_notice(Uuid::new_v4(), "t", "b", "info");
        let call = PushEvent::incoming_call(Uuid::new_v4(), "audio");
        let mut voip = alert_target();
        voip.kind = Kind::Voip;
        for presentation in [Presentation::Quiet, Presentation::Background] {
            assert_eq!(payload(&target, &notice.clone().with_presentation(presentation), &context).unwrap_err(), Error::Payload);
            assert_eq!(payload(&voip, &call.clone().with_presentation(presentation), &context).unwrap_err(), Error::Payload);
            assert_eq!(headers(Kind::Voip, &call.clone().with_presentation(presentation)).push_type, "voip");
        }
        assert_eq!(headers(Kind::Voip, &call), Headers { push_type: "voip", priority: 10, collapse_id: None });
        assert_eq!(headers(Kind::Alert, &notice), Headers { push_type: "alert", priority: 10, collapse_id: None });
    }

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
                priority: 10,
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
                    priority: 10,
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
