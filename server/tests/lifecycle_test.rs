//! Этап I: message lifecycle events (reply/edit/delete/reaction) as rows in
//! the existing `messages` table, delivered through the existing envelope/
//! WS/`/sync` machinery - no new endpoints. The server never sees plaintext
//! (these tests only ever assert on the routing/validation metadata: kind,
//! target_message_id, sequence - never on decrypted content, which doesn't
//! exist server-side at all).

use std::net::SocketAddr;

use axum::extract::ConnectInfo;
use axum::http::StatusCode;
use base64::{engine::general_purpose::STANDARD, Engine as _};
use serde_json::{json, Value};
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
    let redis_url = std::env::var("TEST_REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379/1".to_string());

    let db = sqlx::postgres::PgPoolOptions::new().max_connections(5).connect(&database_url).await.expect("connect to test database");
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
    format!("{prefix}{}", Uuid::new_v4().simple()).chars().take(32).collect()
}

fn connect_info() -> ConnectInfo<SocketAddr> {
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    ConnectInfo(SocketAddr::from(([10, octets[0], octets[1], octets[2]], 12345)))
}

fn register_request(ip: ConnectInfo<SocketAddr>, username: &str, display_name: &str) -> axum::http::Request<axum::body::Body> {
    let body = json!({
        "username": username,
        "password": "correct-horse-battery",
        "display_name": display_name,
        "installation_id": Uuid::new_v4(),
        "device_platform": "android",
        "device_name": "Test Device",
    });
    axum::http::Request::builder()
        .method("POST")
        .uri("/auth/register")
        .header("content-type", "application/json")
        .extension(ip)
        .body(axum::body::Body::from(body.to_string()))
        .unwrap()
}

fn get_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: Option<&str>) -> axum::http::Request<axum::body::Body> {
    let mut builder = axum::http::Request::builder().method("GET").uri(uri).extension(ip);
    if let Some(token) = token {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }
    builder.body(axum::body::Body::empty()).unwrap()
}

fn post_json_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: &str, body: Value) -> axum::http::Request<axum::body::Body> {
    axum::http::Request::builder()
        .method("POST")
        .uri(uri)
        .header("content-type", "application/json")
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(axum::body::Body::from(body.to_string()))
        .unwrap()
}

async fn body_json(response: axum::response::Response) -> Value {
    use http_body_util::BodyExt;
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
}

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

async fn create_direct_chat(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, other_user_id: &str) -> String {
    let response =
        app.clone().oneshot(post_json_request("/chats/direct", ip, token, json!({ "other_user_id": other_user_id }))).await.unwrap();
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

/// A plain "content" send - same shape existing messages_test.rs uses.
fn content_body(client_message_id: Uuid, recipient_device_id: &str, plaintext: &str) -> Value {
    json!({
        "client_message_id": client_message_id,
        "envelopes": [envelope(recipient_device_id, 2, plaintext)],
    })
}

/// Content message that is also a reply (target_message_id set, kind stays
/// implicit "content" default - the server never needs an explicit "reply"
/// kind, replies are just content messages with a reference).
fn reply_body(client_message_id: Uuid, recipient_device_id: &str, plaintext: &str, target_message_id: Uuid) -> Value {
    json!({
        "client_message_id": client_message_id,
        "envelopes": [envelope(recipient_device_id, 2, plaintext)],
        "target_message_id": target_message_id,
    })
}

/// A lifecycle event (edit/delete/reaction) targeting an existing message.
fn lifecycle_body(client_message_id: Uuid, recipient_device_id: &str, plaintext: &str, kind: &str, target_message_id: Uuid) -> Value {
    json!({
        "client_message_id": client_message_id,
        "envelopes": [envelope(recipient_device_id, 2, plaintext)],
        "kind": kind,
        "target_message_id": target_message_id,
    })
}

async fn send(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, chat_id: &str, body: Value) -> axum::response::Response {
    app.clone().oneshot(post_json_request(&format!("/chats/{chat_id}/messages"), ip, token, body)).await.unwrap()
}

// ---------------------------------------------------------------------------
// Reply
// ---------------------------------------------------------------------------

#[tokio::test]
async fn reply_to_valid_target_in_same_chat_is_accepted() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "rep_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "rep_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Hello")).await;
    assert_eq!(original.status(), StatusCode::OK);
    let original_id = body_json(original).await["id"].as_str().unwrap().to_string();

    let response =
        send(&app, ip, &token_a, &chat_id, reply_body(Uuid::new_v4(), &device_b, "Hi", Uuid::parse_str(&original_id).unwrap())).await;
    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn reply_to_message_in_a_different_chat_is_not_found() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "repx_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "repx_b_").await;
    let (_token_c, id_c, device_c, _u_c) = register_user(&app, ip, "repx_c_").await;
    let chat_ab = create_direct_chat(&app, ip, &token_a, &id_b).await;
    let chat_ac = create_direct_chat(&app, ip, &token_a, &id_c).await;

    let in_ab = send(&app, ip, &token_a, &chat_ab, content_body(Uuid::new_v4(), &device_b, "In AB")).await;
    let in_ab_id = body_json(in_ab).await["id"].as_str().unwrap().to_string();

    let response =
        send(&app, ip, &token_a, &chat_ac, reply_body(Uuid::new_v4(), &device_c, "Cross-chat reply", Uuid::parse_str(&in_ab_id).unwrap()))
            .await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn reply_to_nonexistent_message_is_not_found() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "repn_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "repn_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let response = send(&app, ip, &token_a, &chat_id, reply_body(Uuid::new_v4(), &device_b, "Reply", Uuid::new_v4())).await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

// ---------------------------------------------------------------------------
// Edit
// ---------------------------------------------------------------------------

#[tokio::test]
async fn own_text_message_can_be_edited() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "edit_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "edit_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Helo")).await;
    let original_id = body_json(original).await["id"].as_str().unwrap().to_string();

    let response =
        send(&app, ip, &token_a, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "Hello", "edit", Uuid::parse_str(&original_id).unwrap()))
            .await;
    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn editing_someone_elses_message_is_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "edito_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "edito_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Alice's message")).await;
    let original_id = body_json(original).await["id"].as_str().unwrap().to_string();

    // Bob (not the author) tries to edit Alice's message.
    let device_a_for_bob_send = device_b; // irrelevant target device for this negative test
    let response = send(
        &app,
        ip,
        &token_b,
        &chat_id,
        lifecycle_body(Uuid::new_v4(), &device_a_for_bob_send, "Hacked", "edit", Uuid::parse_str(&original_id).unwrap()),
    )
    .await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND, "editing a foreign message must be hidden as 404, not 403");
}

#[tokio::test]
async fn edit_target_in_a_different_chat_is_not_found() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "editx_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "editx_b_").await;
    let (_token_c, id_c, device_c, _u_c) = register_user(&app, ip, "editx_c_").await;
    let chat_ab = create_direct_chat(&app, ip, &token_a, &id_b).await;
    let chat_ac = create_direct_chat(&app, ip, &token_a, &id_c).await;

    let in_ab = send(&app, ip, &token_a, &chat_ab, content_body(Uuid::new_v4(), &device_b, "In AB")).await;
    let in_ab_id = body_json(in_ab).await["id"].as_str().unwrap().to_string();

    let response =
        send(&app, ip, &token_a, &chat_ac, lifecycle_body(Uuid::new_v4(), &device_c, "Edited", "edit", Uuid::parse_str(&in_ab_id).unwrap()))
            .await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn duplicate_edit_retry_is_idempotent() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "editd_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "editd_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Helo")).await;
    let original_id = Uuid::parse_str(body_json(original).await["id"].as_str().unwrap()).unwrap();

    let edit_client_id = Uuid::new_v4();
    let first = send(&app, ip, &token_a, &chat_id, lifecycle_body(edit_client_id, &device_b, "Hello", "edit", original_id)).await;
    assert_eq!(first.status(), StatusCode::OK);
    let first_body = body_json(first).await;

    let second = send(&app, ip, &token_a, &chat_id, lifecycle_body(edit_client_id, &device_b, "Hello", "edit", original_id)).await;
    assert_eq!(second.status(), StatusCode::OK);
    let second_body = body_json(second).await;

    assert_eq!(first_body["id"], second_body["id"], "retry of the same edit must be the same logical event, not a duplicate");
    assert_eq!(first_body["sequence"], second_body["sequence"]);
}

#[tokio::test]
async fn concurrent_edits_get_distinct_deterministic_sequences() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "editc_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "editc_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Helo")).await;
    let original_id = Uuid::parse_str(body_json(original).await["id"].as_str().unwrap()).unwrap();

    let edit1 = send(&app, ip, &token_a, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "Hello v1", "edit", original_id)).await;
    let edit2 = send(&app, ip, &token_a, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "Hello v2", "edit", original_id)).await;
    assert_eq!(edit1.status(), StatusCode::OK);
    assert_eq!(edit2.status(), StatusCode::OK);
    let seq1 = body_json(edit1).await["sequence"].as_i64().unwrap();
    let seq2 = body_json(edit2).await["sequence"].as_i64().unwrap();
    assert!(seq2 > seq1, "the second edit must get a strictly higher global sequence - this is what makes 'last by sequence wins' deterministic");
}

// ---------------------------------------------------------------------------
// Delete
// ---------------------------------------------------------------------------

#[tokio::test]
async fn own_message_can_be_deleted_for_everyone() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "del_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "del_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Secret")).await;
    let original_id = Uuid::parse_str(body_json(original).await["id"].as_str().unwrap()).unwrap();

    let response = send(&app, ip, &token_a, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "", "delete", original_id)).await;
    assert_eq!(response.status(), StatusCode::OK);

    // The tombstoned original no longer appears in history for a NEW fetch.
    let history = app.oneshot(get_request(&format!("/chats/{chat_id}/messages"), ip, Some(&token_a))).await.unwrap();
    let messages = body_json(history).await["messages"].as_array().unwrap().clone();
    assert!(messages.iter().all(|m| m["id"] != original_id.to_string()), "deleted message must not reappear in history");
}

#[tokio::test]
async fn deleting_someone_elses_message_is_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "delo_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "delo_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Alice's message")).await;
    let original_id = Uuid::parse_str(body_json(original).await["id"].as_str().unwrap()).unwrap();

    let response = send(&app, ip, &token_b, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "", "delete", original_id)).await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn repeated_delete_is_idempotent() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "delr_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "delr_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Secret")).await;
    let original_id = Uuid::parse_str(body_json(original).await["id"].as_str().unwrap()).unwrap();

    let first = send(&app, ip, &token_a, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "", "delete", original_id)).await;
    assert_eq!(first.status(), StatusCode::OK);
    let second = send(&app, ip, &token_a, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "", "delete", original_id)).await;
    assert_eq!(second.status(), StatusCode::OK, "a second delete of an already-deleted message must stay a no-op success, not an error");
}

#[tokio::test]
async fn deleted_message_cannot_be_edited() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "dele_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "dele_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Secret")).await;
    let original_id = Uuid::parse_str(body_json(original).await["id"].as_str().unwrap()).unwrap();
    send(&app, ip, &token_a, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "", "delete", original_id)).await;

    let response = send(&app, ip, &token_a, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "Resurrect", "edit", original_id)).await;
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn deleted_message_cannot_receive_a_new_reaction() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "delrx_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "delrx_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Secret")).await;
    let original_id = Uuid::parse_str(body_json(original).await["id"].as_str().unwrap()).unwrap();
    send(&app, ip, &token_a, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "", "delete", original_id)).await;

    let response = send(&app, ip, &token_b, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "heart", "reaction", original_id)).await;
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

// ---------------------------------------------------------------------------
// Reaction
// ---------------------------------------------------------------------------

#[tokio::test]
async fn chat_member_can_react_to_a_message() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "react_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "react_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Hello")).await;
    let original_id = Uuid::parse_str(body_json(original).await["id"].as_str().unwrap()).unwrap();

    let response = send(&app, ip, &token_b, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "heart", "reaction", original_id)).await;
    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn non_member_cannot_react() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "reactn_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "reactn_b_").await;
    let (token_c, _id_c, _device_c, _u_c) = register_user(&app, ip, "reactn_c_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Hello")).await;
    let original_id = Uuid::parse_str(body_json(original).await["id"].as_str().unwrap()).unwrap();

    let response = send(&app, ip, &token_c, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "heart", "reaction", original_id)).await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn changing_a_reaction_is_accepted_as_a_new_event() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "reactc_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "reactc_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Hello")).await;
    let original_id = Uuid::parse_str(body_json(original).await["id"].as_str().unwrap()).unwrap();

    let heart = send(&app, ip, &token_b, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "heart", "reaction", original_id)).await;
    let laugh = send(&app, ip, &token_b, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "laugh", "reaction", original_id)).await;
    assert_eq!(heart.status(), StatusCode::OK);
    assert_eq!(laugh.status(), StatusCode::OK);
    let seq_heart = body_json(heart).await["sequence"].as_i64().unwrap();
    let seq_laugh = body_json(laugh).await["sequence"].as_i64().unwrap();
    assert!(seq_laugh > seq_heart);
}

#[tokio::test]
async fn removing_a_reaction_is_accepted_as_a_new_event() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "reactrm_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "reactrm_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Hello")).await;
    let original_id = Uuid::parse_str(body_json(original).await["id"].as_str().unwrap()).unwrap();

    send(&app, ip, &token_b, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "heart", "reaction", original_id)).await;
    let remove = send(&app, ip, &token_b, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "remove", "reaction", original_id)).await;
    assert_eq!(remove.status(), StatusCode::OK);
}

#[tokio::test]
async fn concurrent_reaction_events_are_strictly_ordered() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "reacto_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "reacto_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Hello")).await;
    let original_id = Uuid::parse_str(body_json(original).await["id"].as_str().unwrap()).unwrap();

    let mut sequences = Vec::new();
    for emoji in ["a", "b", "c"] {
        let response = send(&app, ip, &token_b, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, emoji, "reaction", original_id)).await;
        assert_eq!(response.status(), StatusCode::OK);
        sequences.push(body_json(response).await["sequence"].as_i64().unwrap());
    }
    let mut sorted = sequences.clone();
    sorted.sort();
    assert_eq!(sequences, sorted, "reaction events for the same target must get strictly increasing sequences in send order");
}

// ---------------------------------------------------------------------------
// Sync
// ---------------------------------------------------------------------------

#[tokio::test]
async fn lifecycle_events_are_returned_through_sync() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, device_a, _u_a) = register_user(&app, ip, "sync_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "sync_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Helo")).await;
    let original_id = Uuid::parse_str(body_json(original).await["id"].as_str().unwrap()).unwrap();
    // Also encrypt an envelope to the sender's OWN other device so Alice's
    // own device A can observe the edit event through /sync too.
    let edit_body = json!({
        "client_message_id": Uuid::new_v4(),
        "envelopes": [envelope(&device_a, 2, "Hello")],
        "kind": "edit",
        "target_message_id": original_id,
    });
    let edit_response = send(&app, ip, &token_a, &chat_id, edit_body).await;
    assert_eq!(edit_response.status(), StatusCode::OK);

    let sync_response = app.oneshot(get_request("/sync?after_sequence=0", ip, Some(&token_a))).await.unwrap();
    assert_eq!(sync_response.status(), StatusCode::OK);
    let body = body_json(sync_response).await;
    let messages = body["messages"].as_array().unwrap();
    let edit_event = messages.iter().find(|m| m["kind"] == "edit").expect("edit event must be present in /sync");
    assert_eq!(edit_event["target_message_id"], original_id.to_string());
}

#[tokio::test]
async fn history_includes_kind_and_target_message_id() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "hist_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "hist_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Hello")).await;
    let original_id = Uuid::parse_str(body_json(original).await["id"].as_str().unwrap()).unwrap();
    send(&app, ip, &token_a, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "heart", "reaction", original_id)).await;

    // Fetch as Bob (device_b) - the actual recipient of the envelopes above;
    // history/sync are device-scoped, and Alice's own device never got a
    // self-addressed envelope in this test.
    let history = app.oneshot(get_request(&format!("/chats/{chat_id}/messages"), ip, Some(&token_b))).await.unwrap();
    let body = body_json(history).await;
    let messages = body["messages"].as_array().unwrap();
    let content_msg = messages.iter().find(|m| m["id"] == original_id.to_string()).unwrap();
    assert_eq!(content_msg["kind"], "content");
    let reaction_msg = messages.iter().find(|m| m["kind"] == "reaction").expect("reaction event must appear in history");
    assert_eq!(reaction_msg["target_message_id"], original_id.to_string());
}

#[tokio::test]
async fn duplicate_lifecycle_event_via_retry_is_not_duplicated_in_sync() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "syncd_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "syncd_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "Hello")).await;
    let original_id = Uuid::parse_str(body_json(original).await["id"].as_str().unwrap()).unwrap();

    let reaction_client_id = Uuid::new_v4();
    send(&app, ip, &token_a, &chat_id, lifecycle_body(reaction_client_id, &device_b, "heart", "reaction", original_id)).await;
    // Simulate a client retry after a lost response (same client_message_id).
    send(&app, ip, &token_a, &chat_id, lifecycle_body(reaction_client_id, &device_b, "heart", "reaction", original_id)).await;

    // Fetch as Bob (device_b) - the actual recipient of the envelope.
    let sync_response = app.oneshot(get_request("/sync?after_sequence=0", ip, Some(&token_b))).await.unwrap();
    let body = body_json(sync_response).await;
    let messages = body["messages"].as_array().unwrap();
    let reaction_events: Vec<&Value> = messages.iter().filter(|m| m["kind"] == "reaction").collect();
    assert_eq!(reaction_events.len(), 1, "a retried reaction event must appear exactly once via /sync");
}

// ---------------------------------------------------------------------------
// Attachments/regression
// ---------------------------------------------------------------------------

#[tokio::test]
async fn deleting_an_attachment_message_leaks_no_key_material() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "delatt_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "delatt_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    // A plain content message stands in for an attachment message here -
    // the server never distinguishes attachment vs text content at all
    // (crypto metadata lives entirely inside the opaque ciphertext), so
    // this exercises the exact same delete code path an attachment message
    // would use, with no separate server-side attachment-aware branch to
    // regress.
    let original = send(&app, ip, &token_a, &chat_id, content_body(Uuid::new_v4(), &device_b, "fake-attachment-metadata-blob")).await;
    let original_id = Uuid::parse_str(body_json(original).await["id"].as_str().unwrap()).unwrap();
    let delete_response = send(&app, ip, &token_a, &chat_id, lifecycle_body(Uuid::new_v4(), &device_b, "", "delete", original_id)).await;
    assert_eq!(delete_response.status(), StatusCode::OK);

    let history = app.oneshot(get_request(&format!("/chats/{chat_id}/messages"), ip, Some(&token_a))).await.unwrap();
    let body = body_json(history).await;
    let dump = body.to_string();
    assert!(!dump.contains("fake-attachment-metadata-blob"), "deleted content ciphertext must never leak into any server-visible field");
}
