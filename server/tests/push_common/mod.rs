// Shared helpers for the K (FCM push) test suites. Never talks to Google:
// every network boundary is faked.

use std::net::SocketAddr;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode};
use axum::Router;
use http_body_util::BodyExt;
use serde_json::{json, Value};
use sqlx::Connection;
use tower::ServiceExt;
use uuid::Uuid;

use messenger_server::config::{Config, S3Config};
use messenger_server::push::fcm::{
    AccessToken, AccessTokenSource, FcmTransport, TransportResponse,
};
use messenger_server::push::models::{PushEvent, PushProvider, PushSendOutcome};
use messenger_server::state::AppState;

// ---------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------

#[derive(Clone)]
pub struct RecordedPush {
    pub token: String,
    pub event: PushEvent,
}

/// Records every send and returns a configurable (or fixed) outcome. Never
/// reaches the network.
pub struct FakePushProvider {
    pub sent: Arc<Mutex<Vec<RecordedPush>>>,
    pub outcome: Arc<Mutex<PushSendOutcome>>,
    pub delay: Option<Duration>,
}

impl FakePushProvider {
    pub fn new(outcome: PushSendOutcome) -> Self {
        Self {
            sent: Arc::new(Mutex::new(Vec::new())),
            outcome: Arc::new(Mutex::new(outcome)),
            delay: None,
        }
    }

    pub fn recorded(&self) -> Vec<RecordedPush> {
        self.sent.lock().unwrap().clone()
    }

    pub fn set_outcome(&self, outcome: PushSendOutcome) {
        *self.outcome.lock().unwrap() = outcome;
    }
}

#[async_trait::async_trait]
impl PushProvider for FakePushProvider {
    async fn send(
        &self,
        token: &str,
        event: &PushEvent,
    ) -> Result<PushSendOutcome, messenger_server::push::PushError> {
        if let Some(delay) = self.delay {
            tokio::time::sleep(delay).await;
        }
        self.sent.lock().unwrap().push(RecordedPush {
            token: token.to_string(),
            event: event.clone(),
        });
        Ok(*self.outcome.lock().unwrap())
    }
}

#[derive(Clone)]
pub struct RecordedFcmRequest {
    pub url: String,
    pub bearer: String,
    pub body: String,
}

/// Scripted FCM transport for unit-testing the FcmPushProvider/OAuth layer.
pub struct FakeFcmTransport {
    pub requests: Arc<Mutex<Vec<RecordedFcmRequest>>>,
    pub responses: Arc<Mutex<std::collections::VecDeque<(u16, String)>>>,
}

impl FakeFcmTransport {
    pub fn new() -> Self {
        Self {
            requests: Arc::new(Mutex::new(Vec::new())),
            responses: Arc::new(Mutex::new(Default::default())),
        }
    }

    pub fn push_response(&self, status: u16, body: &str) {
        self.responses
            .lock()
            .unwrap()
            .push_back((status, body.to_string()));
    }

    pub fn recorded(&self) -> Vec<RecordedFcmRequest> {
        self.requests.lock().unwrap().clone()
    }
}

#[async_trait::async_trait]
impl FcmTransport for FakeFcmTransport {
    async fn post(
        &self,
        url: &str,
        bearer: &str,
        content_type: &str,
        body: &str,
    ) -> Result<TransportResponse, String> {
        self.requests.lock().unwrap().push(RecordedFcmRequest {
            url: url.to_string(),
            bearer: bearer.to_string(),
            body: body.to_string(),
        });
        let _ = content_type;
        let response = self
            .responses
            .lock()
            .unwrap()
            .pop_front()
            .unwrap_or((200, "{}".to_string()));
        Ok(TransportResponse {
            status: response.0,
            body: response.1,
        })
    }
}

/// Fake OAuth token source that counts how many times it actually fetched a
/// fresh token - used to prove caching + single-flight.
pub struct FakeTokenSource {
    pub fetch_count: Arc<AtomicU32>,
    pub delay: Option<Duration>,
}

impl FakeTokenSource {
    pub fn new() -> Self {
        Self {
            fetch_count: Arc::new(AtomicU32::new(0)),
            delay: None,
        }
    }

    pub fn count(&self) -> u32 {
        self.fetch_count.load(Ordering::SeqCst)
    }
}

#[async_trait::async_trait]
impl AccessTokenSource for FakeTokenSource {
    async fn fetch_token(&self) -> Result<AccessToken, messenger_server::push::PushError> {
        if let Some(delay) = self.delay {
            tokio::time::sleep(delay).await;
        }
        self.fetch_count.fetch_add(1, Ordering::SeqCst);
        Ok(AccessToken {
            value: format!("fake-token-{}", self.fetch_count.load(Ordering::SeqCst)),
            expires_at: std::time::Instant::now() + Duration::from_secs(3600),
        })
    }
}

// ---------------------------------------------------------------------------
// State + HTTP plumbing
// ---------------------------------------------------------------------------

pub fn test_s3_config() -> S3Config {
    let endpoint =
        std::env::var("S3_ENDPOINT_URL").unwrap_or_else(|_| "http://localhost:9000".to_string());
    S3Config {
        max_upload_duration_seconds: std::env::var("S3_MAX_UPLOAD_DURATION_SECONDS")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(0),
        upload_access_key_id: std::env::var("S3_UPLOAD_ACCESS_KEY_ID").unwrap_or_default(),
        upload_secret_access_key: std::env::var("S3_UPLOAD_SECRET_ACCESS_KEY").unwrap_or_default(),

        endpoint_url: endpoint.clone(),
        public_endpoint_url: endpoint,
        region: "us-east-1".to_string(),
        bucket: "messenger-attachments-test".to_string(),
        access_key_id: "messenger_minio".to_string(),
        secret_access_key: "messenger_minio_secret".to_string(),
        presign_ttl_seconds: 600,
    }
}

pub fn test_turn_config() -> messenger_server::config::TurnConfig {
    messenger_server::config::TurnConfig {
        stun_urls: vec!["stun:127.0.0.1:3478".to_string()],
        turn_urls: vec!["turn:127.0.0.1:3478?transport=udp".to_string()],
        shared_secret: "test-turn-secret".to_string(),
        credential_ttl_seconds: 3600,
    }
}

pub async fn spawn_state_with_provider(provider: Arc<dyn PushProvider>) -> AppState {
    dotenvy::dotenv().ok();

    let database_url = std::env::var("TEST_DATABASE_URL").unwrap_or_else(|_| {
        "postgres://messenger:messenger@localhost:5432/messenger_test".to_string()
    });
    let redis_url =
        std::env::var("TEST_REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379/1".to_string());

    let db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(5)
        .connect(&database_url)
        .await
        .expect("connect to test database");
    sqlx::migrate!()
        .run(&db)
        .await
        .expect("run migrations on test database");

    let redis_client = redis::Client::open(redis_url).expect("build redis client");
    let redis = redis_client
        .get_connection_manager()
        .await
        .expect("connect to test redis");

    let config = Config {
        host: "127.0.0.1".to_string(),
        port: 0,
        database_url,
        database_max_connections: 5,
        redis_url: String::new(),
        jwt_secret: "test-only-secret-do-not-use-in-production".to_string(),
        trusted_proxies: Vec::new(),
        jwt_issuer: "messenger-server-test".to_string(),
        jwt_audience: "messenger-clients-test".to_string(),
        access_token_ttl_seconds: 900,
        refresh_token_ttl_seconds: 2_592_000,
        s3: test_s3_config(),
        turn: test_turn_config(),
        fcm: Default::default(),
    };

    let s3 = std::sync::Arc::new(messenger_server::storage::S3Client::new(config.s3.clone()));
    s3.ensure_bucket()
        .await
        .expect("ensure test attachments bucket exists");

    AppState {
        resources: Default::default(),
        db,
        redis,
        config,
        ws_registry: Default::default(),
        s3,
        push: messenger_server::push::build_with_provider(provider),
    }
}

pub fn unique_username(prefix: &str) -> String {
    format!("{prefix}{}", Uuid::new_v4().simple())
        .chars()
        .take(32)
        .collect()
}

pub fn connect_info() -> ConnectInfo<SocketAddr> {
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    ConnectInfo(SocketAddr::from((
        [10, octets[0], octets[1], octets[2]],
        12345,
    )))
}

pub fn post_json_request(
    uri: &str,
    ip: ConnectInfo<SocketAddr>,
    token: &str,
    body: Value,
) -> Request<Body> {
    Request::builder()
        .method("POST")
        .uri(uri)
        .header("content-type", "application/json")
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(Body::from(body.to_string()))
        .unwrap()
}

pub fn put_json_request(
    uri: &str,
    ip: ConnectInfo<SocketAddr>,
    token: &str,
    body: Value,
) -> Request<Body> {
    Request::builder()
        .method("PUT")
        .uri(uri)
        .header("content-type", "application/json")
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(Body::from(body.to_string()))
        .unwrap()
}

pub fn post_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: &str) -> Request<Body> {
    Request::builder()
        .method("POST")
        .uri(uri)
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(Body::empty())
        .unwrap()
}

pub async fn body_json(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
}

/// Returns (access_token, user_id, device_id).
pub async fn register_user(
    app: &Router,
    ip: ConnectInfo<SocketAddr>,
    prefix: &str,
) -> (String, String, String) {
    let (token, user, device, _username) = register_user_full(app, ip, prefix).await;
    (token, user, device)
}

/// Returns (access_token, user_id, device_id, username).
pub async fn register_user_full(
    app: &Router,
    ip: ConnectInfo<SocketAddr>,
    prefix: &str,
) -> (String, String, String, String) {
    let username = unique_username(prefix);
    let body = json!({
        "username": username,
        "password": "correct-horse-battery",
        "display_name": "Test User",
        "installation_id": Uuid::new_v4(),
        "device_platform": "android",
        "device_name": "Test Device",
    });
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/auth/register")
                .header("content-type", "application/json")
                .extension(ip)
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        body["device_id"].as_str().unwrap().to_string(),
        username,
    )
}

/// Logs an existing user in from a brand-new device (returns the access token
/// for the new device). Used for multi-device call-push scenarios.
pub async fn login_second_device(
    app: &Router,
    ip: ConnectInfo<SocketAddr>,
    username: &str,
) -> String {
    let body = json!({
        "username": username,
        "password": "correct-horse-battery",
        "installation_id": Uuid::new_v4(),
        "device_platform": "android",
        "device_name": "Second Device",
    });
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/auth/login")
                .header("content-type", "application/json")
                .extension(ip)
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await["access_token"]
        .as_str()
        .unwrap()
        .to_string()
}

/// Returns outbox rows as (device_id, event_type, payload).
pub async fn outbox_rows(db: &sqlx::PgPool) -> Vec<(Uuid, String, Value)> {
    sqlx::query_as("SELECT device_id, event_type, payload FROM push_outbox ORDER BY created_at")
        .fetch_all(db)
        .await
        .unwrap()
}

/// Registers an FCM token for the authenticated device.
pub async fn register_push_token(
    app: &Router,
    ip: ConnectInfo<SocketAddr>,
    token: &str,
    fcm_token: &str,
) {
    let response = app
        .clone()
        .oneshot(put_json_request(
            "/devices/me/push-token",
            ip,
            token,
            json!({ "provider": "fcm", "token": fcm_token }),
        ))
        .await
        .unwrap();
    assert_eq!(
        response.status(),
        StatusCode::OK,
        "push token registration should succeed"
    );
}

pub async fn create_direct_chat(
    app: &Router,
    ip: ConnectInfo<SocketAddr>,
    token: &str,
    other_user_id: &str,
) -> String {
    let response = app
        .clone()
        .oneshot(post_json_request(
            "/chats/direct",
            ip,
            token,
            json!({ "other_user_id": other_user_id }),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await["chat_id"]
        .as_str()
        .unwrap()
        .to_string()
}

/// Reads the current push_tokens row for a device (token, invalidated_at).
pub async fn push_token_row(db: &sqlx::PgPool, device_id: &str) -> (String, bool) {
    let row: (String, Option<chrono::DateTime<chrono::Utc>>) =
        sqlx::query_as("SELECT token, invalidated_at FROM push_tokens WHERE device_id = $1")
            .bind(Uuid::parse_str(device_id).unwrap())
            .fetch_one(db)
            .await
            .unwrap();
    (row.0, row.1.is_some())
}

pub async fn outbox_count(db: &sqlx::PgPool) -> i64 {
    let row: (i64,) = sqlx::query_as("SELECT count(*) FROM push_outbox")
        .fetch_one(db)
        .await
        .unwrap();
    row.0
}

// ---------------------------------------------------------------------------
// Cross-test serialization
// ---------------------------------------------------------------------------

/// The push tests share `push_outbox` and a global worker (`process_due`), so
/// parallel execution would make one test observe/claim another test's rows. A
/// single Postgres advisory lock serializes every DB-backed push test across
/// all test binaries. The lock is held on a dedicated connection; dropping it
/// closes the socket and releases the lock.
pub struct TestLock {
    conn: Option<sqlx::PgConnection>,
}

async fn acquire_test_lock(key: i64) -> TestLock {
    let database_url = std::env::var("TEST_DATABASE_URL").unwrap_or_else(|_| {
        "postgres://messenger:messenger@localhost:5432/messenger_test".to_string()
    });
    let mut conn = sqlx::PgConnection::connect(&database_url)
        .await
        .expect("connect for test lock");
    sqlx::query("SELECT pg_advisory_lock($1)")
        .bind(key)
        .execute(&mut conn)
        .await
        .expect("acquire advisory test lock");
    TestLock { conn: Some(conn) }
}

/// Serializes the test and returns both the lock (kept alive for the whole
/// test) and a ready AppState wired to `provider`. Clears the global
/// `push_outbox` so each serialized test starts from a clean slate (only
/// outbox tests hold the lock; other suites never touch this table).
pub async fn locked_state(provider: Arc<dyn PushProvider>) -> (TestLock, AppState) {
    let lock = acquire_test_lock(0x4B_5445_5354).await; // "KTEST"
    let state = spawn_state_with_provider(provider).await;
    sqlx::query("DELETE FROM push_outbox")
        .execute(&state.db)
        .await
        .expect("clear push_outbox");
    (lock, state)
}
