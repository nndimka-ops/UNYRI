use std::net::SocketAddr;
use std::time::Duration;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use futures_util::StreamExt;
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

fn register_request(ip: ConnectInfo<SocketAddr>, username: &str) -> Request<Body> {
    let body = json!({
        "username": username,
        "password": "correct-horse-battery",
        "display_name": "Test User",
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

async fn body_json(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
}

async fn register_user(app: &axum::Router, ip: ConnectInfo<SocketAddr>, prefix: &str) -> (String, String, String, String) {
    let username = unique_username(prefix);
    let response = app.clone().oneshot(register_request(ip, &username)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        body["device_id"].as_str().unwrap().to_string(),
        username,
    )
}

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

fn envelope(recipient_device_id: &str, message_type: i16, plaintext: &str) -> Value {
    json!({
        "recipient_device_id": recipient_device_id,
        "message_type": message_type,
        "ciphertext": STANDARD.encode(plaintext.as_bytes()),
    })
}

fn send_body_to(client_message_id: Uuid, recipient_device_id: &str, plaintext: &str) -> Value {
    json!({ "client_message_id": client_message_id, "envelopes": [envelope(recipient_device_id, 2, plaintext)] })
}

async fn send_message(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, chat_id: &str, recipient_device_id: &str, text: &str) -> i64 {
    let uri = format!("/chats/{chat_id}/messages");
    let response = app
        .clone()
        .oneshot(post_json_request(&uri, ip, token, send_body_to(Uuid::new_v4(), recipient_device_id, text)))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await["sequence"].as_i64().unwrap()
}

async fn mark_delivered(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, chat_id: &str, sequence: i64) -> axum::response::Response {
    let uri = format!("/chats/{chat_id}/delivered");
    app.clone().oneshot(post_json_request(&uri, ip, token, json!({ "sequence": sequence }))).await.unwrap()
}

async fn mark_read(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, chat_id: &str, sequence: i64) -> axum::response::Response {
    let uri = format!("/chats/{chat_id}/read");
    app.clone().oneshot(post_json_request(&uri, ip, token, json!({ "sequence": sequence }))).await.unwrap()
}

async fn get_chat(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, chat_id: &str) -> Value {
    let uri = format!("/chats/{chat_id}");
    let response = app.clone().oneshot(get_request(&uri, ip, Some(token))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await
}

#[tokio::test]
async fn delivered_watermark_marks_receipts_up_to_sequence() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "deliv_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "deliv_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let mut sequences = Vec::new();
    for text in ["one", "two", "three"] {
        sequences.push(send_message(&app, ip, &token_a, &chat_id, &device_b, text).await);
    }

    let response = mark_delivered(&app, ip, &token_b, &chat_id, sequences[1]).await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["sequence"], sequences[1]);

    let delivered_count: (i64,) = sqlx::query_as(
        "SELECT COUNT(*) FROM message_receipts mr JOIN messages m ON m.id = mr.message_id \
         WHERE m.chat_id = $1 AND mr.user_id = $2 AND mr.delivered_at IS NOT NULL",
    )
    .bind(Uuid::parse_str(&chat_id).unwrap())
    .bind(Uuid::parse_str(&id_b).unwrap())
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(delivered_count.0, 2, "only the first two messages should be marked delivered");
}

#[tokio::test]
async fn delivered_watermark_cannot_regress() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "delivback_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "delivback_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let mut sequences = Vec::new();
    for text in ["one", "two", "three"] {
        sequences.push(send_message(&app, ip, &token_a, &chat_id, &device_b, text).await);
    }

    let forward = mark_delivered(&app, ip, &token_b, &chat_id, sequences[2]).await;
    assert_eq!(body_json(forward).await["sequence"], sequences[2]);

    let backward = mark_delivered(&app, ip, &token_b, &chat_id, sequences[0]).await;
    assert_eq!(backward.status(), StatusCode::OK);
    assert_eq!(body_json(backward).await["sequence"], sequences[2], "watermark must never move backwards");
}

#[tokio::test]
async fn read_watermark_cannot_regress_and_implies_delivered() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "readback_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "readback_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let mut sequences = Vec::new();
    for text in ["one", "two"] {
        sequences.push(send_message(&app, ip, &token_a, &chat_id, &device_b, text).await);
    }

    let forward = mark_read(&app, ip, &token_b, &chat_id, sequences[1]).await;
    assert_eq!(body_json(forward).await["sequence"], sequences[1]);

    let backward = mark_read(&app, ip, &token_b, &chat_id, sequences[0]).await;
    assert_eq!(body_json(backward).await["sequence"], sequences[1]);

    let delivered_count: (i64,) = sqlx::query_as(
        "SELECT COUNT(*) FROM message_receipts mr JOIN messages m ON m.id = mr.message_id \
         WHERE m.chat_id = $1 AND mr.user_id = $2 AND mr.delivered_at IS NOT NULL",
    )
    .bind(Uuid::parse_str(&chat_id).unwrap())
    .bind(Uuid::parse_str(&id_b).unwrap())
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(delivered_count.0, 2, "marking read must imply delivered");
}

#[tokio::test]
async fn cannot_mark_delivered_or_read_for_a_foreign_chat() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "foreign_a_").await;
    let (_token_b, id_b, _device_b, _u_b) = register_user(&app, ip, "foreign_b_").await;
    let (token_c, _id_c, _device_c, _u_c) = register_user(&app, ip, "foreign_c_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let delivered = mark_delivered(&app, ip, &token_c, &chat_id, 1).await;
    assert_eq!(delivered.status(), StatusCode::NOT_FOUND);
    let read = mark_read(&app, ip, &token_c, &chat_id, 1).await;
    assert_eq!(read.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn delivered_sequence_is_scoped_to_its_own_chat() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "scopedeliv_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "scopedeliv_b_").await;
    let (token_c, id_c, _device_c, _u_c) = register_user(&app, ip, "scopedeliv_c_").await;
    let (_token_d, id_d, device_d, _u_d) = register_user(&app, ip, "scopedeliv_d_").await;

    let chat_ab = create_direct_chat(&app, ip, &token_a, &id_b).await;
    let chat_cd = create_direct_chat(&app, ip, &token_c, &id_d).await;

    let seq_ab = send_message(&app, ip, &token_a, &chat_ab, &device_b, "for b").await;
    // A high global sequence in an unrelated chat must not affect chat_ab's receipts.
    let seq_cd = send_message(&app, ip, &token_c, &chat_cd, &device_d, "for d").await;
    assert!(seq_cd > seq_ab);

    let response = mark_delivered(&app, ip, &token_b, &chat_ab, seq_cd).await;
    assert_eq!(response.status(), StatusCode::OK);
    // Effective sequence reported is whatever the subquery over chat_ab found (only seq_ab exists there).
    let body = body_json(response).await;
    assert_eq!(body["sequence"], seq_cd, "the requested watermark is accepted even if higher than any message in this chat - it's just a ceiling");

    let delivered_count: (i64,) = sqlx::query_as(
        "SELECT COUNT(*) FROM message_receipts mr JOIN messages m ON m.id = mr.message_id \
         WHERE m.chat_id = $1 AND mr.delivered_at IS NOT NULL",
    )
    .bind(Uuid::parse_str(&chat_ab).unwrap())
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(delivered_count.0, 1, "only chat_ab's own message receipt must be affected, never chat_cd's");
    let _ = id_c;
}

#[tokio::test]
async fn get_chat_reconciles_delivered_and_read_watermarks_even_without_ws() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "reconcile_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "reconcile_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let mut sequences = Vec::new();
    for text in ["one", "two", "three"] {
        sequences.push(send_message(&app, ip, &token_a, &chat_id, &device_b, text).await);
    }

    mark_delivered(&app, ip, &token_b, &chat_id, sequences[2]).await;
    mark_read(&app, ip, &token_b, &chat_id, sequences[0]).await;

    // A never opens a WebSocket at all - pure HTTP reconciliation.
    let chat = get_chat(&app, ip, &token_a, &chat_id).await;
    assert_eq!(chat["other_member_delivered_sequence"], sequences[2]);
    assert_eq!(chat["other_member_read_sequence"], sequences[0]);
    assert_eq!(chat["own_read_sequence"], 0, "recipient cursor must not leak into sender cursor");
    let recipient_chat = get_chat(&app, ip, &token_b, &chat_id).await;
    assert_eq!(recipient_chat["own_read_sequence"], sequences[0], "restore account read position after login");
}

#[tokio::test]
async fn multi_device_recipient_read_is_a_user_level_aggregate() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "multiread_a_").await;
    let (token_b1, id_b, device_b1, username_b) = register_user(&app, ip, "multiread_b_").await;
    let (_token_b2, _device_b2) = login_new_device(&app, ip, &username_b).await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let seq = send_message(&app, ip, &token_a, &chat_id, &device_b1, "hello").await;

    // Only B1 (phone) reads it; B2 (tablet) stays completely offline/unused.
    mark_read(&app, ip, &token_b1, &chat_id, seq).await;

    let chat = get_chat(&app, ip, &token_a, &chat_id).await;
    assert_eq!(chat["other_member_read_sequence"], seq, "one device reading is enough for the user-level read receipt to reflect it");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 8)]
async fn concurrent_delivered_updates_remain_monotonic() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "concdeliv_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "concdeliv_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let mut sequences = Vec::new();
    for i in 0..10 {
        sequences.push(send_message(&app, ip, &token_a, &chat_id, &device_b, &format!("m{i}")).await);
    }

    let mut handles = Vec::new();
    for &seq in &sequences {
        let app = app.clone();
        let ip = ip;
        let token = token_b.clone();
        let chat_id = chat_id.clone();
        handles.push(tokio::spawn(async move { mark_delivered(&app, ip, &token, &chat_id, seq).await }));
    }
    for handle in handles {
        let response = handle.await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
    }

    let (max_delivered,): (i64,) = sqlx::query_as(
        "SELECT MAX(mr.sequence) FROM message_receipts mr JOIN messages m ON m.id = mr.message_id \
         WHERE m.chat_id = $1 AND mr.user_id = $2 AND mr.delivered_at IS NOT NULL",
    )
    .bind(Uuid::parse_str(&chat_id).unwrap())
    .bind(Uuid::parse_str(&id_b).unwrap())
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(max_delivered, *sequences.iter().max().unwrap(), "concurrent delivered calls must converge to the max, never a lower value");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 8)]
async fn concurrent_read_updates_remain_monotonic() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "concread_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "concread_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let mut sequences = Vec::new();
    for i in 0..10 {
        sequences.push(send_message(&app, ip, &token_a, &chat_id, &device_b, &format!("m{i}")).await);
    }

    let mut handles = Vec::new();
    for &seq in &sequences {
        let app = app.clone();
        let ip = ip;
        let token = token_b.clone();
        let chat_id = chat_id.clone();
        handles.push(tokio::spawn(async move { mark_read(&app, ip, &token, &chat_id, seq).await }));
    }
    for handle in handles {
        let response = handle.await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
    }

    let (max_read,): (i64,) = sqlx::query_as(
        "SELECT MAX(mr.sequence) FROM message_receipts mr JOIN messages m ON m.id = mr.message_id \
         WHERE m.chat_id = $1 AND mr.user_id = $2 AND mr.read_at IS NOT NULL",
    )
    .bind(Uuid::parse_str(&chat_id).unwrap())
    .bind(Uuid::parse_str(&id_b).unwrap())
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(max_read, *sequences.iter().max().unwrap(), "concurrent read calls must converge to the max, never a lower value");
}

// ---------------------------------------------------------------------------
// WebSocket receipt.updated realtime tests - real TCP listener, same pattern
// as messages_test.rs.
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

fn ws_request(addr: SocketAddr, token: &str) -> tokio_tungstenite::tungstenite::http::Request<()> {
    let mut request = format!("ws://{addr}/ws").into_client_request().unwrap();
    request.headers_mut().insert("Authorization", HeaderValue::from_str(&format!("Bearer {token}")).unwrap());
    request
}

#[tokio::test]
async fn websocket_delivers_receipt_updated_event_to_sender() {
    let state = spawn_state().await;
    let ip = connect_info();
    let http_app = messenger_server::app(state.clone());
    let (token_a, _id_a, _device_a, _u_a) = register_user(&http_app, ip, "wsrecv_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&http_app, ip, "wsrecv_b_").await;
    let chat_id = create_direct_chat(&http_app, ip, &token_a, &id_b).await;
    let seq = send_message(&http_app, ip, &token_a, &chat_id, &device_b, "hello").await;
    let addr = spawn_server(state).await;

    let (mut ws_a, _) = tokio_tungstenite::connect_async(ws_request(addr, &token_a)).await.unwrap();

    mark_delivered(&http_app, ip, &token_b, &chat_id, seq).await;

    let frame = tokio::time::timeout(Duration::from_secs(2), ws_a.next()).await.unwrap().unwrap().unwrap();
    let WsMessage::Text(text) = frame else { panic!("expected a text frame") };
    let payload: Value = serde_json::from_str(&text).unwrap();
    assert_eq!(payload["type"], "receipt.updated");
    assert_eq!(payload["chat_id"], chat_id);
    assert_eq!(payload["user_id"], id_b);
    assert_eq!(payload["delivered_sequence"], seq);
    assert_eq!(payload["read_sequence"], 0);
}

#[tokio::test]
async fn websocket_does_not_deliver_receipt_event_to_unrelated_user() {
    let state = spawn_state().await;
    let ip = connect_info();
    let http_app = messenger_server::app(state.clone());
    let (token_a, _id_a, _device_a, _u_a) = register_user(&http_app, ip, "wsrecvnm_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&http_app, ip, "wsrecvnm_b_").await;
    let (token_c, _id_c, _device_c, _u_c) = register_user(&http_app, ip, "wsrecvnm_c_").await;
    let chat_id = create_direct_chat(&http_app, ip, &token_a, &id_b).await;
    let seq = send_message(&http_app, ip, &token_a, &chat_id, &device_b, "hello").await;
    let addr = spawn_server(state).await;

    let (mut ws_c, _) = tokio_tungstenite::connect_async(ws_request(addr, &token_c)).await.unwrap();

    mark_delivered(&http_app, ip, &token_b, &chat_id, seq).await;

    let result = tokio::time::timeout(Duration::from_millis(500), ws_c.next()).await;
    assert!(result.is_err(), "an unrelated user must never receive another chat's receipt event");
}
