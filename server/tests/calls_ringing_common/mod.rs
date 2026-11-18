// Shared harness for the ringing-confirmation suites (docs/CALL_RINGING_SPEC.md).
// Two binaries use it because the kill switch is read once per process.
#![allow(dead_code)]

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode};
use futures_util::StreamExt;
use http_body_util::BodyExt;
use serde_json::{json, Value};
use tokio::net::TcpStream;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tokio_tungstenite::{MaybeTlsStream, WebSocketStream};
use tower::ServiceExt;
use uuid::Uuid;

use messenger_server::config::{Config, S3Config, TurnConfig};
use messenger_server::push::{PushEvent, PushProvider, PushSendOutcome, PushService};
use messenger_server::state::AppState;

pub type Ws = WebSocketStream<MaybeTlsStream<TcpStream>>;

fn test_s3_config() -> S3Config {
    let endpoint = std::env::var("S3_ENDPOINT_URL").unwrap_or_else(|_| "http://localhost:9000".to_string());
    S3Config {
        max_upload_duration_seconds: 0,
        upload_access_key_id: String::new(),
        upload_secret_access_key: String::new(),
        endpoint_url: endpoint.clone(),
        public_endpoint_url: endpoint,
        region: "us-east-1".to_string(),
        bucket: "messenger-attachments-test".to_string(),
        access_key_id: "messenger_minio".to_string(),
        secret_access_key: "messenger_minio_secret".to_string(),
        presign_ttl_seconds: 600,
    }
}

fn test_turn_config() -> TurnConfig {
    TurnConfig {
        stun_urls: vec!["stun:127.0.0.1:3478".to_string()],
        turn_urls: vec!["turn:127.0.0.1:3478?transport=udp".to_string()],
        shared_secret: "test-turn-secret".to_string(),
        credential_ttl_seconds: 3600,
    }
}

/// A push provider that is configured but never asked to send: no outbox
/// worker runs in these tests. It enables token registration and outbox checks
/// without contacting FCM; expectation must stay independent of token state.
struct IdleProvider;

#[async_trait::async_trait]
impl PushProvider for IdleProvider {
    async fn send(&self, _token: &str, _event: &PushEvent) -> Result<PushSendOutcome, messenger_server::push::PushError> {
        Ok(PushSendOutcome::Delivered)
    }
}

pub async fn spawn_state() -> AppState {
    build_state(messenger_server::push::disabled()).await
}

/// FCM configured with a stub, without starting a delivery worker.
pub async fn spawn_state_with_push() -> AppState {
    build_state(messenger_server::push::build_with_provider(Arc::new(IdleProvider))).await
}

async fn build_state(push: Arc<PushService>) -> AppState {
    dotenvy::dotenv().ok();
    let database_url = std::env::var("TEST_DATABASE_URL")
        .unwrap_or_else(|_| "postgres://messenger:messenger@localhost:5432/messenger_test".to_string());
    let redis_url = std::env::var("TEST_REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379/1".to_string());
    let db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(5)
        .connect(&database_url)
        .await
        .expect("connect to test database");
    sqlx::migrate!().run(&db).await.expect("run migrations on test database");
    let redis_client = redis::Client::open(redis_url).expect("build redis client");
    let redis = redis_client.get_connection_manager().await.expect("connect to test redis");
    let config = Config {
        identity: Default::default(),
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
    let s3 = Arc::new(messenger_server::storage::S3Client::new(config.s3.clone()));
    s3.ensure_bucket().await.expect("ensure test attachments bucket exists");
    AppState { resources: Default::default(), db, redis, config, ws_registry: Default::default(), s3, push }
}

pub fn unique_username(prefix: &str) -> String {
    format!("{prefix}{}", Uuid::new_v4().simple()).chars().take(32).collect()
}

pub fn connect_info() -> ConnectInfo<SocketAddr> {
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    ConnectInfo(SocketAddr::from(([10, octets[0], octets[1], octets[2]], 12345)))
}

pub fn request(method: &str, uri: &str, ip: ConnectInfo<SocketAddr>, token: &str, body: Option<Value>) -> Request<Body> {
    let builder = Request::builder()
        .method(method)
        .uri(uri)
        .header("authorization", format!("Bearer {token}"))
        .extension(ip);
    match body {
        Some(body) => builder.header("content-type", "application/json").body(Body::from(body.to_string())).unwrap(),
        None => builder.body(Body::empty()).unwrap(),
    }
}

pub async fn body_json(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    if bytes.is_empty() { Value::Null } else { serde_json::from_slice(&bytes).unwrap() }
}

/// Sends a request and returns its status and JSON body.
pub async fn call(app: &axum::Router, method: &str, uri: &str, ip: ConnectInfo<SocketAddr>, token: &str, body: Option<Value>) -> (StatusCode, Value) {
    let response = app.clone().oneshot(request(method, uri, ip, token, body)).await.unwrap();
    let status = response.status();
    (status, body_json(response).await)
}

#[derive(Clone, Debug)]
pub struct User {
    pub token: String,
    pub id: String,
    pub device: String,
    pub username: String,
}

impl User {
    pub fn uuid(&self) -> Uuid { Uuid::parse_str(&self.id).unwrap() }
    pub fn device_uuid(&self) -> Uuid { Uuid::parse_str(&self.device).unwrap() }
}

fn auth_body(username: &str, device_name: &str) -> Value {
    json!({
        "username": username,
        "password": "correct-horse-battery",
        "display_name": "Test User",
        "installation_id": Uuid::new_v4(),
        "device_platform": "ios",
        "device_name": device_name,
    })
}

/// A fresh address per call: registration allows only a few per address.
async fn auth_post(app: &axum::Router, _ip: ConnectInfo<SocketAddr>, uri: &str, body: Value) -> Value {
    let ip = connect_info();
    let request = Request::builder()
        .method("POST")
        .uri(uri)
        .header("content-type", "application/json")
        .extension(ip)
        .body(Body::from(body.to_string()))
        .unwrap();
    let response = app.clone().oneshot(request).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK, "{uri} must succeed");
    body_json(response).await
}

pub async fn register_user(app: &axum::Router, ip: ConnectInfo<SocketAddr>, prefix: &str) -> User {
    let username = unique_username(prefix);
    let body = auth_post(app, ip, "/auth/register", auth_body(&username, "Phone")).await;
    User {
        token: body["access_token"].as_str().unwrap().to_string(),
        id: body["user"]["id"].as_str().unwrap().to_string(),
        device: body["device_id"].as_str().unwrap().to_string(),
        username,
    }
}

/// The same account signed in on another device.
pub async fn second_device(app: &axum::Router, ip: ConnectInfo<SocketAddr>, user: &User) -> User {
    let body = auth_post(app, ip, "/auth/login", auth_body(&user.username, "Tablet")).await;
    User {
        token: body["access_token"].as_str().unwrap().to_string(),
        id: user.id.clone(),
        device: body["device_id"].as_str().unwrap().to_string(),
        username: user.username.clone(),
    }
}

pub async fn create_direct_chat(app: &axum::Router, ip: ConnectInfo<SocketAddr>, user: &User, other: &User) {
    let (status, _) = call(app, "POST", "/chats/direct", ip, &user.token, Some(json!({ "other_user_id": other.id }))).await;
    assert_eq!(status, StatusCode::OK);
}

pub async fn try_create_call(app: &axum::Router, ip: ConnectInfo<SocketAddr>, caller: &User, callee: &User) -> (StatusCode, Value) {
    call(app, "POST", "/calls", ip, &caller.token, Some(json!({ "callee_user_id": callee.id, "media_kind": "audio" }))).await
}

pub async fn create_call(app: &axum::Router, ip: ConnectInfo<SocketAddr>, caller: &User, callee: &User) -> Value {
    let (status, body) = try_create_call(app, ip, caller, callee).await;
    assert_eq!(status, StatusCode::OK, "call creation must succeed: {body}");
    body
}

pub fn call_id(call: &Value) -> String {
    call["id"].as_str().unwrap().to_string()
}

/// `POST /calls/{id}/ringing`.
pub async fn confirm(app: &axum::Router, ip: ConnectInfo<SocketAddr>, user: &User, call_id: &str) -> (StatusCode, Value) {
    call(app, "POST", &format!("/calls/{call_id}/ringing"), ip, &user.token, None).await
}

pub async fn get(app: &axum::Router, ip: ConnectInfo<SocketAddr>, user: &User, uri: &str) -> Value {
    let (status, body) = call(app, "GET", uri, ip, &user.token, None).await;
    assert_eq!(status, StatusCode::OK, "GET {uri}: {body}");
    body
}

pub async fn spawn_server(state: AppState) -> SocketAddr {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let app = messenger_server::app(state);
    tokio::spawn(async move {
        axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>()).await.unwrap();
    });
    addr
}

/// Opens a socket; `capabilities` is the raw `X-Unyri-Capabilities` value.
pub async fn connect_ws(addr: SocketAddr, user: &User, capabilities: Option<&str>) -> Ws {
    let mut request = format!("ws://{addr}/ws").into_client_request().unwrap();
    request.headers_mut().insert("Authorization", HeaderValue::from_str(&format!("Bearer {}", user.token)).unwrap());
    if let Some(value) = capabilities {
        request.headers_mut().insert("X-Unyri-Capabilities", HeaderValue::from_str(value).unwrap());
    }
    let (ws, _) = tokio_tungstenite::connect_async(request).await.unwrap();
    ws
}

/// A current build: advertises the capability.
pub async fn connect_capable(addr: SocketAddr, state: &AppState, user: &User) -> Ws {
    let ws = connect_ws(addr, user, Some("call-ring-ack")).await;
    wait_online(state, user, true).await;
    ws
}

/// An older build (or today's Android): no header.
pub async fn connect_legacy(addr: SocketAddr, state: &AppState, user: &User) -> Ws {
    let ws = connect_ws(addr, user, None).await;
    wait_online(state, user, true).await;
    ws
}

/// Closes a socket and waits until the server dropped it, so its per-device
/// socket slot is free again.
pub async fn close_ws(mut ws: Ws) {
    ws.close(None).await.ok();
    let _ = tokio::time::timeout(Duration::from_secs(2), async { while let Some(Ok(_)) = ws.next().await {} }).await;
}

/// The upgrade response can reach the client before the server registers the
/// socket, so wait for the registry instead of racing it.
pub async fn wait_online(state: &AppState, user: &User, online: bool) {
    let device = user.device_uuid();
    for _ in 0..200 {
        if state.ws_registry.online(&[device]).await == online { return; }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    panic!("device never became {}", if online { "online" } else { "offline" });
}

/// Every text frame that arrives within `window`.
pub async fn frames_within(ws: &mut Ws, window: Duration) -> Vec<Value> {
    let deadline = tokio::time::Instant::now() + window;
    let mut frames = Vec::new();
    loop {
        match tokio::time::timeout_at(deadline, ws.next()).await {
            Err(_) => return frames,
            Ok(Some(Ok(WsMessage::Text(text)))) => frames.push(serde_json::from_str(&text).unwrap()),
            Ok(Some(Ok(_))) => {}
            Ok(Some(Err(_))) | Ok(None) => return frames,
        }
    }
}

/// The first frame of `kind` within `window`, skipping others.
pub async fn next_of_type(ws: &mut Ws, kind: &str, window: Duration) -> Option<Value> {
    let deadline = tokio::time::Instant::now() + window;
    loop {
        match tokio::time::timeout_at(deadline, ws.next()).await {
            Err(_) => return None,
            Ok(Some(Ok(WsMessage::Text(text)))) => {
                let frame: Value = serde_json::from_str(&text).unwrap();
                if frame["type"] == kind { return Some(frame); }
            }
            Ok(Some(Ok(_))) => {}
            Ok(Some(Err(_))) | Ok(None) => return None,
        }
    }
}

pub fn count_of_type(frames: &[Value], kind: &str) -> usize {
    frames.iter().filter(|frame| frame["type"] == kind).count()
}

/// Moves a call's creation back in time, so the timing floor has passed.
pub async fn age_call(db: &sqlx::PgPool, call_id: &str, seconds: i32) {
    sqlx::query("UPDATE calls SET created_at = created_at - make_interval(secs => $2) WHERE id = $1")
        .bind(Uuid::parse_str(call_id).unwrap())
        .bind(f64::from(seconds))
        .execute(db)
        .await
        .unwrap();
}

/// (callee_ack_expected, callee_ringing).
pub async fn bits(db: &sqlx::PgPool, call_id: &str) -> (bool, bool) {
    sqlx::query_as("SELECT callee_ack_expected, callee_ringing FROM calls WHERE id = $1")
        .bind(Uuid::parse_str(call_id).unwrap())
        .fetch_one(db)
        .await
        .unwrap()
}

pub async fn ring_ack_flag(db: &sqlx::PgPool, user: &User) -> bool {
    sqlx::query_scalar("SELECT call_ring_ack FROM devices WHERE id = $1")
        .bind(user.device_uuid())
        .fetch_one(db)
        .await
        .unwrap()
}

/// Seconds since the call was created, by the database clock.
pub async fn call_age_seconds(db: &sqlx::PgPool, call_id: &str) -> f64 {
    sqlx::query_scalar("SELECT EXTRACT(EPOCH FROM (now() - created_at))::float8 FROM calls WHERE id = $1")
        .bind(Uuid::parse_str(call_id).unwrap())
        .fetch_one(db)
        .await
        .unwrap()
}

pub async fn set_online_visibility(db: &sqlx::PgPool, owner: &User, visibility: &str, never_allow: &[Uuid]) {
    sqlx::query(
        "INSERT INTO user_privacy (user_id, online_visibility, never_allow) VALUES ($1, $2, $3) \
         ON CONFLICT (user_id) DO UPDATE SET online_visibility = EXCLUDED.online_visibility, never_allow = EXCLUDED.never_allow",
    )
    .bind(owner.uuid())
    .bind(visibility)
    .bind(never_allow)
    .execute(db)
    .await
    .unwrap();
}

pub fn has_ringing_fields(call: &Value) -> bool {
    call.get("peer_ringing").is_some() || call.get("peer_ringing_expected").is_some()
}
