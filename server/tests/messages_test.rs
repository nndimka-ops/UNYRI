use std::net::SocketAddr;
use std::time::Duration;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use futures_util::{SinkExt, StreamExt};
use http_body_util::BodyExt;
use serde_json::{json, Value};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tower::ServiceExt;
use uuid::Uuid;

use messenger_server::config::{Config, S3Config};
use messenger_server::state::AppState;

fn test_s3_config() -> S3Config {
    let endpoint = std::env::var("S3_ENDPOINT_URL").unwrap_or_else(|_| "http://localhost:9000".to_string());
    S3Config {
        max_upload_duration_seconds: std::env::var("S3_MAX_UPLOAD_DURATION_SECONDS").ok().and_then(|v|v.parse().ok()).unwrap_or(0),
        upload_access_key_id: std::env::var("S3_UPLOAD_ACCESS_KEY_ID").unwrap_or_default(), upload_secret_access_key: std::env::var("S3_UPLOAD_SECRET_ACCESS_KEY").unwrap_or_default(),

        endpoint_url: endpoint.clone(),
        public_endpoint_url: endpoint,
        region: "us-east-1".to_string(),
        bucket: "messenger-attachments-test".to_string(),
        access_key_id: "messenger_minio".to_string(),
        secret_access_key: "messenger_minio_secret".to_string(),
        presign_ttl_seconds: 600,
    }
}

fn test_turn_config() -> messenger_server::config::TurnConfig {
    messenger_server::config::TurnConfig {
        stun_urls: vec!["stun:127.0.0.1:3478".to_string()],
        turn_urls: vec!["turn:127.0.0.1:3478?transport=udp".to_string()],
        shared_secret: "test-turn-secret".to_string(),
        credential_ttl_seconds: 3600,
    }
}

async fn spawn_state() -> AppState {
    dotenvy::dotenv().ok();

    let database_url = std::env::var("TEST_DATABASE_URL")
        .unwrap_or_else(|_| "postgres://messenger:messenger@localhost:5432/messenger_test".to_string());
    let redis_url =
        std::env::var("TEST_REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379/1".to_string());

    let db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(5)
        .connect(&database_url)
        .await
        .expect("connect to test database");
    sqlx::migrate!().run(&db).await.expect("run migrations on test database");

    let redis_client = redis::Client::open(redis_url).expect("build redis client");
    let redis = redis_client.get_connection_manager().await.expect("connect to test redis");

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
    s3.ensure_bucket().await.expect("ensure test attachments bucket exists");

    AppState { resources: Default::default(), db, redis, config, ws_registry: Default::default(), s3, push: messenger_server::push::disabled() }
}

fn unique_username(prefix: &str) -> String {
    format!("{prefix}{}", Uuid::new_v4().simple())
        .chars()
        .take(32)
        .collect()
}

fn connect_info() -> ConnectInfo<SocketAddr> {
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    ConnectInfo(SocketAddr::from(([10, octets[0], octets[1], octets[2]], 12345)))
}

fn register_request(ip: ConnectInfo<SocketAddr>, username: &str, display_name: &str) -> Request<Body> {
    let body = json!({
        "username": username,
        "password": "correct-horse-battery",
        "display_name": display_name,
        "installation_id": Uuid::new_v4(),
        "device_platform": "android",
        "device_name": "Test Device",
    });
    Request::builder()
        .method("POST")
        .uri("/auth/register")
        .header("content-type", "application/json")
        .extension(ip)
        .body(Body::from(body.to_string()))
        .unwrap()
}

fn login_request(ip: ConnectInfo<SocketAddr>, username: &str) -> Request<Body> {
    let body = json!({
        "username": username,
        "password": "correct-horse-battery",
        "installation_id": Uuid::new_v4(),
        "device_platform": "android",
        "device_name": "Second Device",
    });
    Request::builder()
        .method("POST")
        .uri("/auth/login")
        .header("content-type", "application/json")
        .extension(ip)
        .body(Body::from(body.to_string()))
        .unwrap()
}

fn get_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: Option<&str>) -> Request<Body> {
    let mut builder = Request::builder().method("GET").uri(uri).extension(ip);
    if let Some(token) = token {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }
    builder.body(Body::empty()).unwrap()
}

fn post_json_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: &str, body: Value) -> Request<Body> {
    Request::builder()
        .method("POST")
        .uri(uri)
        .header("content-type", "application/json")
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(Body::from(body.to_string()))
        .unwrap()
}

fn post_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: &str) -> Request<Body> {
    Request::builder()
        .method("POST")
        .uri(uri)
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(Body::empty())
        .unwrap()
}

async fn body_json(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
}

/// Registers a fresh user/device and returns (access_token, user_id, device_id, username).
async fn register_user(app: &axum::Router, ip: ConnectInfo<SocketAddr>, prefix: &str) -> (String, String, String, String) {
    let username = unique_username(prefix);
    let response = app.clone().oneshot(register_request(ip, &username, "Test User")).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        body["device_id"].as_str().unwrap().to_string(),
        username,
    )
}

/// Logs the same account in from a brand-new device (multi-device), returns (access_token, device_id).
async fn login_new_device(app: &axum::Router, ip: ConnectInfo<SocketAddr>, username: &str) -> (String, String) {
    let response = app.clone().oneshot(login_request(ip, username)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (body["access_token"].as_str().unwrap().to_string(), body["device_id"].as_str().unwrap().to_string())
}

async fn create_direct_chat(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, other_user_id: &str) -> String {
    let response = app
        .clone()
        .oneshot(post_json_request("/chats/direct", ip, token, json!({ "other_user_id": other_user_id })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await["chat_id"].as_str().unwrap().to_string()
}

/// A single fake ciphertext envelope. The server never interprets the bytes
/// cryptographically, so tests just base64-encode plain text as a stand-in
/// for what would really be `SessionCipher.encrypt()` output.
fn envelope(recipient_device_id: &str, message_type: i16, plaintext: &str) -> Value {
    json!({
        "recipient_device_id": recipient_device_id,
        "message_type": message_type,
        "ciphertext": STANDARD.encode(plaintext.as_bytes()),
    })
}

fn send_body(client_message_id: Uuid, envelopes: Vec<Value>) -> Value {
    json!({ "client_message_id": client_message_id, "envelopes": envelopes })
}

/// Convenience for the common case: one envelope addressed to a single
/// recipient device, `message_type = 2` (an already-established session).
fn send_body_to(client_message_id: Uuid, recipient_device_id: &str, plaintext: &str) -> Value {
    send_body(client_message_id, vec![envelope(recipient_device_id, 2, plaintext)])
}

fn decode_ciphertext(message: &Value) -> String {
    let bytes = STANDARD.decode(message["ciphertext"].as_str().unwrap()).unwrap();
    String::from_utf8(bytes).unwrap()
}

#[tokio::test]
async fn send_message_creates_message() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "send_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "send_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let uri = format!("/chats/{chat_id}/messages");
    let response = app
        .oneshot(post_json_request(&uri, ip, &token_a, send_body_to(Uuid::new_v4(), &device_b, "Hello")))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;

    assert_eq!(body["chat_id"], chat_id);
    assert_eq!(body["status"], "sent");
    assert!(body["sequence"].as_i64().is_some());
    assert!(body["id"].as_str().is_some());
    // The response never echoes ciphertext back - the sender already has
    // the plaintext locally, it composed the message.
    assert!(body.get("ciphertext").is_none());
}

#[tokio::test]
async fn recipient_can_retrieve_message_via_history() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, device_a, _u_a) = register_user(&app, ip, "recv_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "recv_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let uri = format!("/chats/{chat_id}/messages");
    app.clone().oneshot(post_json_request(&uri, ip, &token_a, send_body_to(Uuid::new_v4(), &device_b, "Hi B"))).await.unwrap();

    let response = app.oneshot(get_request(&uri, ip, Some(&token_b))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    let messages = body["messages"].as_array().unwrap();
    assert_eq!(messages.len(), 1);
    assert_eq!(decode_ciphertext(&messages[0]), "Hi B");
    assert_eq!(messages[0]["message_type"], 2);
    // The recipient needs the sender's device id to pick the right Signal session.
    assert_eq!(messages[0]["sender_device_id"], device_a);
}

#[tokio::test]
async fn message_belongs_to_correct_chat() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "scope_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "scope_b_").await;
    let (_token_c, id_c, _device_c, _u_c) = register_user(&app, ip, "scope_c_").await;
    let chat_ab = create_direct_chat(&app, ip, &token_a, &id_b).await;
    let chat_ac = create_direct_chat(&app, ip, &token_a, &id_c).await;

    app.clone()
        .oneshot(post_json_request(&format!("/chats/{chat_ab}/messages"), ip, &token_a, send_body_to(Uuid::new_v4(), &device_b, "For B")))
        .await
        .unwrap();

    let response =
        app.oneshot(get_request(&format!("/chats/{chat_ac}/messages"), ip, Some(&token_a))).await.unwrap();
    let body = body_json(response).await;
    assert!(body["messages"].as_array().unwrap().is_empty(), "message leaked into the wrong chat");
}

#[tokio::test]
async fn cannot_send_to_someone_elses_chat() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "int_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "int_b_").await;
    let (token_c, _id_c, _device_c, _u_c) = register_user(&app, ip, "int_c_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let uri = format!("/chats/{chat_id}/messages");
    let response = app.oneshot(post_json_request(&uri, ip, &token_c, send_body_to(Uuid::new_v4(), &device_b, "Intruder"))).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn cannot_read_someone_elses_chat() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "rint_a_").await;
    let (_token_b, id_b, _device_b, _u_b) = register_user(&app, ip, "rint_b_").await;
    let (token_c, _id_c, _device_c, _u_c) = register_user(&app, ip, "rint_c_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let response = app.oneshot(get_request(&format!("/chats/{chat_id}/messages"), ip, Some(&token_c))).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn send_to_nonexistent_chat_is_not_found() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "nx_a_").await;

    let uri = format!("/chats/{}/messages", Uuid::new_v4());
    let response = app.oneshot(post_json_request(&uri, ip, &token_a, send_body_to(Uuid::new_v4(), &Uuid::new_v4().to_string(), "Hello"))).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn read_history_of_nonexistent_chat_is_not_found() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "nxr_a_").await;

    let uri = format!("/chats/{}/messages", Uuid::new_v4());
    let response = app.oneshot(get_request(&uri, ip, Some(&token_a))).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn empty_envelopes_is_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "noenv_a_").await;
    let (_token_b, id_b, _device_b, _u_b) = register_user(&app, ip, "noenv_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let uri = format!("/chats/{chat_id}/messages");
    let response = app.oneshot(post_json_request(&uri, ip, &token_a, send_body(Uuid::new_v4(), vec![]))).await.unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn too_many_envelopes_is_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "toomany_a_").await;
    let (_token_b, id_b, _device_b, _u_b) = register_user(&app, ip, "toomany_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let envelopes: Vec<Value> = (0..33).map(|_| envelope(&Uuid::new_v4().to_string(), 2, "x")).collect();
    let uri = format!("/chats/{chat_id}/messages");
    let response = app.oneshot(post_json_request(&uri, ip, &token_a, send_body(Uuid::new_v4(), envelopes))).await.unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn invalid_message_type_is_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "badtype_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "badtype_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let uri = format!("/chats/{chat_id}/messages");
    let body = send_body(Uuid::new_v4(), vec![envelope(&device_b, 5, "Hello")]);
    let response = app.oneshot(post_json_request(&uri, ip, &token_a, body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn invalid_base64_ciphertext_is_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "badb64_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "badb64_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let uri = format!("/chats/{chat_id}/messages");
    let body = json!({
        "client_message_id": Uuid::new_v4(),
        "envelopes": [{ "recipient_device_id": device_b, "message_type": 2, "ciphertext": "not-valid-base64!!!" }],
    });
    let response = app.oneshot(post_json_request(&uri, ip, &token_a, body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn envelope_to_device_outside_chat_is_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "outside_a_").await;
    let (_token_b, id_b, _device_b, _u_b) = register_user(&app, ip, "outside_b_").await;
    let (_token_c, _id_c, device_c, _u_c) = register_user(&app, ip, "outside_c_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    // C is a real device, but not a member of the A-B chat.
    let uri = format!("/chats/{chat_id}/messages");
    let response = app.oneshot(post_json_request(&uri, ip, &token_a, send_body_to(Uuid::new_v4(), &device_c, "Hello"))).await.unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn envelope_to_nonexistent_device_is_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "ghost_a_").await;
    let (_token_b, id_b, _device_b, _u_b) = register_user(&app, ip, "ghost_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let uri = format!("/chats/{chat_id}/messages");
    let response = app.oneshot(post_json_request(&uri, ip, &token_a, send_body_to(Uuid::new_v4(), &Uuid::new_v4().to_string(), "Hello"))).await.unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn invalid_client_message_id_is_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "uuid_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "uuid_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let uri = format!("/chats/{chat_id}/messages");
    let body = json!({ "client_message_id": "not-a-uuid", "envelopes": [envelope(&device_b, 2, "Hello")] });
    let response = app.oneshot(post_json_request(&uri, ip, &token_a, body)).await.unwrap();
    assert!(response.status().is_client_error(), "expected a 4xx for a malformed UUID");
}

#[tokio::test]
async fn duplicate_client_message_id_returns_the_same_message() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "dup_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "dup_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;
    let client_message_id = Uuid::new_v4();

    let uri = format!("/chats/{chat_id}/messages");
    let first = app.clone().oneshot(post_json_request(&uri, ip, &token_a, send_body_to(client_message_id, &device_b, "Once"))).await.unwrap();
    assert_eq!(first.status(), StatusCode::OK);
    let first_body = body_json(first).await;

    let second = app.oneshot(post_json_request(&uri, ip, &token_a, send_body_to(client_message_id, &device_b, "Once"))).await.unwrap();
    assert_eq!(second.status(), StatusCode::OK);
    let second_body = body_json(second).await;

    assert_eq!(first_body["id"], second_body["id"]);
    assert_eq!(first_body["sequence"], second_body["sequence"]);

    let count: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM messages WHERE chat_id = $1")
        .bind(Uuid::parse_str(&chat_id).unwrap())
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(count.0, 1);

    // The second (duplicate) call must not have inserted a second envelope either.
    let envelope_count: (i64,) = sqlx::query_as(
        "SELECT COUNT(*) FROM message_envelopes me JOIN messages m ON m.id = me.message_id WHERE m.chat_id = $1",
    )
    .bind(Uuid::parse_str(&chat_id).unwrap())
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(envelope_count.0, 1);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 8)]
async fn twenty_concurrent_duplicate_sends_create_exactly_one_message() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "dup20_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "dup20_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;
    let client_message_id = Uuid::new_v4();

    let mut handles = Vec::new();
    for _ in 0..20 {
        let app = app.clone();
        let ip = ip;
        let token = token_a.clone();
        let device_b = device_b.clone();
        let uri = format!("/chats/{chat_id}/messages");
        handles.push(tokio::spawn(async move {
            app.oneshot(post_json_request(&uri, ip, &token, send_body_to(client_message_id, &device_b, "Same"))).await.unwrap()
        }));
    }

    let mut message_ids = std::collections::HashSet::new();
    for handle in handles {
        let response = handle.await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = body_json(response).await;
        message_ids.insert(body["id"].as_str().unwrap().to_string());
    }
    assert_eq!(message_ids.len(), 1, "all 20 responses must reference the same message");

    let count: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM messages WHERE chat_id = $1")
        .bind(Uuid::parse_str(&chat_id).unwrap())
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(count.0, 1, "exactly one message row must exist");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 8)]
async fn concurrent_distinct_sends_get_unique_sequences() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "conc_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "conc_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let mut handles = Vec::new();
    for i in 0..10 {
        let app = app.clone();
        let ip = ip;
        let token = token_a.clone();
        let device_b = device_b.clone();
        let uri = format!("/chats/{chat_id}/messages");
        handles.push(tokio::spawn(async move {
            app.oneshot(post_json_request(&uri, ip, &token, send_body_to(Uuid::new_v4(), &device_b, &format!("msg{i}")))).await.unwrap()
        }));
    }

    let mut sequences = std::collections::HashSet::new();
    for handle in handles {
        let response = handle.await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = body_json(response).await;
        sequences.insert(body["sequence"].as_i64().unwrap());
    }
    assert_eq!(sequences.len(), 10, "every concurrent send must get a unique sequence");
}

#[tokio::test]
async fn messages_are_ordered_by_sequence() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "ord_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "ord_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;
    let uri = format!("/chats/{chat_id}/messages");

    for text in ["first", "second", "third"] {
        let response = app.clone().oneshot(post_json_request(&uri, ip, &token_a, send_body_to(Uuid::new_v4(), &device_b, text))).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
    }

    // History is read from the recipient's perspective (B) - the sender
    // relies on its own local plaintext cache, not server round-trip.
    let response = app.oneshot(get_request(&uri, ip, Some(&token_b))).await.unwrap();
    let body = body_json(response).await;
    let messages = body["messages"].as_array().unwrap();
    assert_eq!(messages.len(), 3);
    // Server returns newest-first (sequence DESC).
    let texts: Vec<String> = messages.iter().map(decode_ciphertext).collect();
    assert_eq!(texts, vec!["third", "second", "first"]);
    let sequences: Vec<i64> = messages.iter().map(|m| m["sequence"].as_i64().unwrap()).collect();
    assert!(sequences.windows(2).all(|w| w[0] > w[1]), "sequences must be strictly descending");
}

#[tokio::test]
async fn history_pagination_via_before_sequence() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "page_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "page_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;
    let uri = format!("/chats/{chat_id}/messages");

    for i in 0..12 {
        let response =
            app.clone().oneshot(post_json_request(&uri, ip, &token_a, send_body_to(Uuid::new_v4(), &device_b, &format!("m{i}")))).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
    }

    let mut seen = std::collections::HashSet::new();
    let mut before: Option<i64> = None;
    loop {
        let page_uri = match before {
            Some(b) => format!("{uri}?limit=5&before_sequence={b}"),
            None => format!("{uri}?limit=5"),
        };
        let response = app.clone().oneshot(get_request(&page_uri, ip, Some(&token_b))).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = body_json(response).await;
        let messages = body["messages"].as_array().unwrap().clone();
        assert!(messages.len() <= 5);
        for m in &messages {
            assert!(seen.insert(m["id"].as_str().unwrap().to_string()), "duplicate message across pages");
        }
        match body["next_before_sequence"].as_i64() {
            Some(next) => before = Some(next),
            None => {
                assert_eq!(body["has_more"], false);
                break;
            }
        }
    }
    assert_eq!(seen.len(), 12, "all messages must be retrievable across pages with no duplicates");
}

#[tokio::test]
async fn offline_recipient_retrieves_missed_messages_via_history() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "off_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "off_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;
    let uri = format!("/chats/{chat_id}/messages");

    // B never opens a WebSocket connection at all - purely HTTP history sync.
    app.clone().oneshot(post_json_request(&uri, ip, &token_a, send_body_to(Uuid::new_v4(), &device_b, "Offline message"))).await.unwrap();

    let response = app.oneshot(get_request(&uri, ip, Some(&token_b))).await.unwrap();
    let body = body_json(response).await;
    let messages = body["messages"].as_array().unwrap();
    assert_eq!(messages.len(), 1);
    assert_eq!(decode_ciphertext(&messages[0]), "Offline message");
}

#[tokio::test]
async fn read_receipt_marks_messages_read() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "read_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "read_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;
    let uri = format!("/chats/{chat_id}/messages");

    let mut last_sequence = 0i64;
    for text in ["one", "two", "three"] {
        let response = app.clone().oneshot(post_json_request(&uri, ip, &token_a, send_body_to(Uuid::new_v4(), &device_b, text))).await.unwrap();
        let body = body_json(response).await;
        last_sequence = body["sequence"].as_i64().unwrap();
    }

    let read_uri = format!("/chats/{chat_id}/read");
    let response = app
        .oneshot(post_json_request(&read_uri, ip, &token_b, json!({ "sequence": last_sequence })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["sequence"], last_sequence);

    let read_count: (i64,) = sqlx::query_as(
        "SELECT COUNT(*) FROM message_receipts mr JOIN messages m ON m.id = mr.message_id \
         WHERE mr.user_id = $1 AND m.chat_id = $2 AND mr.read_at IS NOT NULL",
    )
    .bind(Uuid::parse_str(&id_b).unwrap())
    .bind(Uuid::parse_str(&chat_id).unwrap())
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(read_count.0, 3);
}

#[tokio::test]
async fn read_receipt_cannot_move_backwards() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "back_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "back_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;
    let uri = format!("/chats/{chat_id}/messages");

    let mut sequences = Vec::new();
    for text in ["one", "two", "three"] {
        let response = app.clone().oneshot(post_json_request(&uri, ip, &token_a, send_body_to(Uuid::new_v4(), &device_b, text))).await.unwrap();
        let body = body_json(response).await;
        sequences.push(body["sequence"].as_i64().unwrap());
    }

    let read_uri = format!("/chats/{chat_id}/read");
    let forward = app
        .clone()
        .oneshot(post_json_request(&read_uri, ip, &token_b, json!({ "sequence": sequences[2] })))
        .await
        .unwrap();
    let forward_body = body_json(forward).await;
    assert_eq!(forward_body["sequence"], sequences[2]);

    let backward = app
        .oneshot(post_json_request(&read_uri, ip, &token_b, json!({ "sequence": sequences[0] })))
        .await
        .unwrap();
    assert_eq!(backward.status(), StatusCode::OK);
    let backward_body = body_json(backward).await;
    assert_eq!(backward_body["sequence"], sequences[2], "watermark must not move backwards");
}

#[tokio::test]
async fn revoked_session_cannot_send_or_read_messages() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "rev_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "rev_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let logout = app.clone().oneshot(post_request("/auth/logout", ip, &token_a)).await.unwrap();
    assert_eq!(logout.status(), StatusCode::NO_CONTENT);

    let uri = format!("/chats/{chat_id}/messages");
    let send = app.clone().oneshot(post_json_request(&uri, ip, &token_a, send_body_to(Uuid::new_v4(), &device_b, "Hi"))).await.unwrap();
    assert_eq!(send.status(), StatusCode::UNAUTHORIZED);

    let list = app.oneshot(get_request(&uri, ip, Some(&token_a))).await.unwrap();
    assert_eq!(list.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn sender_other_device_receives_message_sent_not_message_new() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a1, _id_a, device_a1, username_a) = register_user(&app, ip, "multi_a_").await;
    let (_token_a2, device_a2) = login_new_device(&app, ip, &username_a).await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "multi_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a1, &id_b).await;

    let uri = format!("/chats/{chat_id}/messages");
    let envelopes = vec![
        envelope(&device_b, 2, "Hello everyone"),
        envelope(&device_a2, 2, "Hello everyone"),
    ];
    let response = app.oneshot(post_json_request(&uri, ip, &token_a1, send_body(Uuid::new_v4(), envelopes))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    // Both envelopes must actually be stored, one per target device.
    let stored: Vec<(Uuid,)> = sqlx::query_as(
        "SELECT recipient_device_id FROM message_envelopes me JOIN messages m ON m.id = me.message_id WHERE m.chat_id = $1",
    )
    .bind(Uuid::parse_str(&chat_id).unwrap())
    .fetch_all(&state.db)
    .await
    .unwrap();
    let stored_ids: std::collections::HashSet<String> = stored.into_iter().map(|(id,)| id.to_string()).collect();
    assert!(stored_ids.contains(&device_b));
    assert!(stored_ids.contains(&device_a2));
    let _ = device_a1;
}

/// Uploads minimal Signal key material for a device, making it Signal-eligible
/// (so the strict own-device coverage rule starts requiring an envelope for it).
async fn upload_keys_for_device(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str) {
    let response = app
        .clone()
        .oneshot(post_json_request(
            "/keys",
            ip,
            token,
            json!({
                "identity_key": STANDARD.encode(b"fake-identity-key"),
                "registration_id": 12345,
                "signed_prekey": { "key_id": 1, "public_key": STANDARD.encode(b"fake-spk"), "signature": STANDARD.encode(b"fake-sig") },
                "kyber_prekey": { "key_id": 1, "public_key": STANDARD.encode(b"fake-kyber"), "signature": STANDARD.encode(b"fake-ksig") },
                "one_time_prekeys": [{ "key_id": 1, "public_key": STANDARD.encode(b"fake-otp-1") }],
            }),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

/// N.2R regression: a keyless own device must NOT be required as an envelope
/// target (the client literally cannot encrypt for it), but the moment it
/// uploads an identity key it becomes Signal-eligible and omission is then
/// rejected by the strict own-device coverage rule.
#[tokio::test]
async fn keyless_own_device_is_not_required_until_it_uploads_keys() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a1, _id_a, _device_a1, username_a) = register_user(&app, ip, "kld_a_").await;
    let (token_a2, device_a2) = login_new_device(&app, ip, &username_a).await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "kld_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a1, &id_b).await;

    let uri = format!("/chats/{chat_id}/messages");

    // A2 is keyless: sending an envelope for bob only (omitting A2) must pass.
    let ok = app
        .clone()
        .oneshot(post_json_request(&uri, ip, &token_a1, send_body_to(Uuid::new_v4(), &device_b, "keyless ok")))
        .await
        .unwrap();
    assert_eq!(ok.status(), StatusCode::OK, "keyless own device must not block a send");

    // A2 uploads keys -> now Signal-eligible and mandatory.
    upload_keys_for_device(&app, ip, &token_a2).await;

    // Same send shape (bob only, A2 omitted) must now be rejected.
    let rejected = app
        .clone()
        .oneshot(post_json_request(&uri, ip, &token_a1, send_body_to(Uuid::new_v4(), &device_b, "now rejected")))
        .await
        .unwrap();
    assert_eq!(
        rejected.status(),
        StatusCode::BAD_REQUEST,
        "once the own device has an identity key it becomes a required envelope target"
    );

    // And with A2 included it must pass again.
    let accepted = app
        .clone()
        .oneshot(post_json_request(
            &uri,
            ip,
            &token_a1,
            send_body(Uuid::new_v4(), vec![envelope(&device_b, 2, "covered"), envelope(&device_a2, 2, "covered")]),
        ))
        .await
        .unwrap();
    assert_eq!(accepted.status(), StatusCode::OK, "covering the keyed own device must pass");
}

// ---------------------------------------------------------------------------
// WebSocket tests: these spin up a real TCP listener (oneshot's mock service
// can't drive a genuine protocol upgrade), then connect with a real
// WebSocket client so the handshake, auth, and framing are all exercised for
// real, not simulated.
// ---------------------------------------------------------------------------

async fn spawn_server(state: AppState) -> SocketAddr {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let app = messenger_server::app(state);
    tokio::spawn(async move {
        axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>()).await.unwrap();
    });
    addr
}

fn ws_request(addr: SocketAddr, token: Option<&str>) -> tokio_tungstenite::tungstenite::http::Request<()> {
    let mut request = format!("ws://{addr}/ws").into_client_request().unwrap();
    if let Some(token) = token {
        request.headers_mut().insert("Authorization", HeaderValue::from_str(&format!("Bearer {token}")).unwrap());
    }
    request
}

#[tokio::test]
async fn websocket_requires_authentication() {
    let state = spawn_state().await;
    let addr = spawn_server(state).await;

    let result = tokio_tungstenite::connect_async(ws_request(addr, None)).await;
    match result {
        Err(tokio_tungstenite::tungstenite::Error::Http(response)) => {
            assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
        }
        other => panic!("expected an HTTP 401 handshake rejection, got {other:?}"),
    }
}

#[tokio::test]
async fn websocket_connects_with_valid_token() {
    let state = spawn_state().await;
    let ip = connect_info();
    let http_app = messenger_server::app(state.clone());
    let (token, _id, _device, _u) = register_user(&http_app, ip, "wsauth_").await;
    let addr = spawn_server(state).await;

    let (mut ws_stream, _response) = tokio_tungstenite::connect_async(ws_request(addr, Some(&token))).await.unwrap();
    ws_stream.send(WsMessage::Text(r#"{"type":"PING"}"#.to_string())).await.unwrap();
    let reply = tokio::time::timeout(Duration::from_secs(2), ws_stream.next()).await.unwrap().unwrap().unwrap();
    let WsMessage::Text(text) = reply else { panic!("expected a text frame") };
    assert!(text.contains("PONG"));
}

#[tokio::test]
async fn websocket_delivers_new_message_to_recipient_in_realtime() {
    let state = spawn_state().await;
    let ip = connect_info();
    let http_app = messenger_server::app(state.clone());
    let (token_a, _id_a, device_a, _u_a) = register_user(&http_app, ip, "wsrt_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&http_app, ip, "wsrt_b_").await;
    let chat_id = create_direct_chat(&http_app, ip, &token_a, &id_b).await;
    let addr = spawn_server(state).await;

    let (mut ws_b, _) = tokio_tungstenite::connect_async(ws_request(addr, Some(&token_b))).await.unwrap();

    let uri = format!("/chats/{chat_id}/messages");
    http_app.clone().oneshot(post_json_request(&uri, ip, &token_a, send_body_to(Uuid::new_v4(), &device_b, "Realtime hello"))).await.unwrap();

    let frame = tokio::time::timeout(Duration::from_secs(2), ws_b.next()).await.unwrap().unwrap().unwrap();
    let WsMessage::Text(text) = frame else { panic!("expected a text frame") };
    let payload: Value = serde_json::from_str(&text).unwrap();
    assert_eq!(payload["type"], "message.new");
    assert_eq!(decode_ciphertext(&payload["message"]), "Realtime hello");
    assert_eq!(payload["message"]["chat_id"], chat_id);
    assert_eq!(payload["message"]["sender_device_id"], device_a);
}

#[tokio::test]
async fn websocket_reconnect_still_receives_messages() {
    let state = spawn_state().await;
    let ip = connect_info();
    let http_app = messenger_server::app(state.clone());
    let (token_a, _id_a, _device_a, _u_a) = register_user(&http_app, ip, "wsrc_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&http_app, ip, "wsrc_b_").await;
    let chat_id = create_direct_chat(&http_app, ip, &token_a, &id_b).await;
    let addr = spawn_server(state).await;

    // First connection, then a clean disconnect - simulates a dropped
    // connection before the client's exponential-backoff reconnect.
    let (mut first, _) = tokio_tungstenite::connect_async(ws_request(addr, Some(&token_b))).await.unwrap();
    first.close(None).await.unwrap();
    drop(first);
    tokio::time::sleep(Duration::from_millis(200)).await;

    let (mut second, _) = tokio_tungstenite::connect_async(ws_request(addr, Some(&token_b))).await.unwrap();

    let uri = format!("/chats/{chat_id}/messages");
    http_app.clone().oneshot(post_json_request(&uri, ip, &token_a, send_body_to(Uuid::new_v4(), &device_b, "After reconnect"))).await.unwrap();

    let frame = tokio::time::timeout(Duration::from_secs(2), second.next()).await.unwrap().unwrap().unwrap();
    let WsMessage::Text(text) = frame else { panic!("expected a text frame") };
    let payload: Value = serde_json::from_str(&text).unwrap();
    assert_eq!(payload["type"], "message.new");
    assert_eq!(decode_ciphertext(&payload["message"]), "After reconnect");
}

#[tokio::test]
async fn websocket_does_not_deliver_message_to_non_member() {
    let state = spawn_state().await;
    let ip = connect_info();
    let http_app = messenger_server::app(state.clone());
    let (token_a, _id_a, _device_a, _u_a) = register_user(&http_app, ip, "wsnm_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&http_app, ip, "wsnm_b_").await;
    let (token_c, _id_c, _device_c, _u_c) = register_user(&http_app, ip, "wsnm_c_").await;
    let chat_id = create_direct_chat(&http_app, ip, &token_a, &id_b).await;
    let addr = spawn_server(state).await;

    // C is a registered user but not a member of the A-B chat.
    let (mut ws_c, _) = tokio_tungstenite::connect_async(ws_request(addr, Some(&token_c))).await.unwrap();

    let uri = format!("/chats/{chat_id}/messages");
    http_app.clone().oneshot(post_json_request(&uri, ip, &token_a, send_body_to(Uuid::new_v4(), &device_b, "Not for C"))).await.unwrap();

    let result = tokio::time::timeout(Duration::from_millis(500), ws_c.next()).await;
    assert!(result.is_err(), "a non-member must not receive the message event");
}


#[tokio::test]
async fn envelope_failure_rolls_back_message_and_retry_succeeds() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token, _, sender_device, _) = register_user(&app, ip, "atomicmsg_a_").await;
    let (_, peer, device, _) = register_user(&app, ip, "atomicmsg_b_").await;
    let chat = create_direct_chat(&app, ip, &token, &peer).await;
    let client = Uuid::new_v4();
    let hook = format!("fail_envelope_{}", client.simple());
    sqlx::query(&format!("CREATE FUNCTION {hook}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test envelope failure'; END $$"))
        .execute(&state.db).await.unwrap();
    sqlx::query(&format!("CREATE TRIGGER {hook} BEFORE INSERT ON message_envelopes FOR EACH ROW WHEN (NEW.recipient_device_id = '{device}'::uuid) EXECUTE FUNCTION {hook}()"))
        .execute(&state.db).await.unwrap();
    let uri = format!("/chats/{chat}/messages");
    let body = send_body(client, vec![envelope(&sender_device, 2, "first"), envelope(&device, 2, "second")]);
    let response = app.clone().oneshot(post_json_request(&uri, ip, &token, body.clone())).await.unwrap();
    sqlx::query(&format!("DROP TRIGGER {hook} ON message_envelopes")).execute(&state.db).await.unwrap();
    sqlx::query(&format!("DROP FUNCTION {hook}()")).execute(&state.db).await.unwrap();
    assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM messages WHERE client_message_id = $1")
        .bind(client).fetch_one(&state.db).await.unwrap();
    assert_eq!(count, 0, "failed second envelope must roll back the message and first envelope");
    for _ in 0..2 {
        let response = app.clone().oneshot(post_json_request(&uri, ip, &token, body.clone())).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
    }
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM message_envelopes e JOIN messages m ON m.id=e.message_id WHERE m.client_message_id=$1")
        .bind(client).fetch_one(&state.db).await.unwrap();
    assert_eq!(count, 2);
}
