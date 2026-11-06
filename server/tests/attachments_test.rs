use std::net::SocketAddr;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use http_body_util::BodyExt;
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
    format!("{prefix}{}", Uuid::new_v4().simple()).chars().take(32).collect()
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

async fn register_user(app: &axum::Router, ip: ConnectInfo<SocketAddr>, prefix: &str) -> (String, String, String) {
    let username = unique_username(prefix);
    let response = app.clone().oneshot(register_request(ip, &username)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (body["access_token"].as_str().unwrap().to_string(), body["user"]["id"].as_str().unwrap().to_string(), body["device_id"].as_str().unwrap().to_string())
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

async fn create_attachment(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, encrypted_size: i64) -> Value {
    let response = app
        .clone()
        .oneshot(post_json_request(
            "/attachments",
            ip,
            token,
            json!({ "client_attachment_id": Uuid::new_v4(), "encrypted_size": encrypted_size }),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await
}

/// Uploads real bytes to the presigned PUT URL - exercises the actual MinIO
/// round trip, not just the Rust signing code.
async fn upload_bytes(upload_url: &str, bytes: Vec<u8>) {
    let response = reqwest::Client::new().put(upload_url).body(bytes).send().await.unwrap();
    assert!(response.status().is_success(), "upload failed: {}", response.status());
}

async fn complete_attachment(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, attachment_id: &str) -> axum::response::Response {
    let uri = format!("/attachments/{attachment_id}/complete");
    app.clone().oneshot(post_json_request(&uri, ip, token, json!({}))).await.unwrap()
}

async fn download_attachment(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, attachment_id: &str) -> axum::response::Response {
    let uri = format!("/attachments/{attachment_id}/download");
    app.clone().oneshot(get_request(&uri, ip, Some(token))).await.unwrap()
}

fn envelope(recipient_device_id: &str, plaintext: &str) -> Value {
    json!({ "recipient_device_id": recipient_device_id, "message_type": 2, "ciphertext": STANDARD.encode(plaintext.as_bytes()) })
}

async fn upload_and_complete(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, size: usize) -> String {
    let intent = create_attachment(app, ip, token, size as i64).await;
    upload_bytes(intent["upload_url"].as_str().unwrap(), vec![7u8; size]).await;
    let response = complete_attachment(app, ip, token, intent["attachment_id"].as_str().unwrap()).await;
    assert_eq!(response.status(), StatusCode::OK);
    intent["attachment_id"].as_str().unwrap().to_string()
}

// ---------------------------------------------------------------------------
// Attachment creation
// ---------------------------------------------------------------------------

#[tokio::test]
async fn multipart_resumes_acknowledged_parts_and_completes_exact_bytes() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token,_,_) = register_user(&app, ip, "multipart").await;
    let (stranger,_,_) = register_user(&app, connect_info(), "outsider").await;
    let bytes: Vec<u8> = (0..(5*1024*1024+171)).map(|n| (n%251) as u8).collect();
    let intent = create_attachment(&app, ip, &token, bytes.len() as i64).await;
    let id = intent["attachment_id"].as_str().unwrap();
    let uri = format!("/attachments/{id}/multipart");
    let denied = app.clone().oneshot(post_json_request(&uri, ip, &stranger, json!({}))).await.unwrap();
    assert_eq!(denied.status(), StatusCode::NOT_FOUND);
    let first = body_json(app.clone().oneshot(post_json_request(&uri,ip,&token,json!({}))).await.unwrap()).await;
    assert_eq!(first["parts"].as_array().unwrap().len(), 2);
    let incomplete = complete_attachment(&app,ip,&token,id).await;
    assert_eq!(incomplete.status(), StatusCode::BAD_REQUEST);
    for n in 0..2 {
        // A fresh request mimics a new client/process after an interrupted transfer.
        let plan = body_json(app.clone().oneshot(post_json_request(&uri,ip,&token,json!({}))).await.unwrap()).await;
        if n == 1 { assert_eq!(plan["parts"][0]["complete"], true); }
        let part = &plan["parts"][n];
        let start = n*5*1024*1024;
        let end = bytes.len().min(start+5*1024*1024);
        let response = reqwest::Client::new().put(part["upload_url"].as_str().unwrap()).body(bytes[start..end].to_vec()).send().await.unwrap();
        assert!(response.status().is_success());
        let etag = response.headers()["etag"].to_str().unwrap().to_string();
        let ack_uri = format!("/attachments/{id}/parts/{}",n+1);
        for _ in 0..2 {
            let ack = app.clone().oneshot(post_json_request(&ack_uri,ip,&token,json!({"etag":etag}))).await.unwrap();
            assert_eq!(ack.status(),StatusCode::OK);
        }
    }
    assert_eq!(complete_attachment(&app,ip,&token,id).await.status(),StatusCode::OK);
    assert_eq!(complete_attachment(&app,ip,&token,id).await.status(),StatusCode::OK);
    let info = body_json(download_attachment(&app,ip,&token,id).await).await;
    let actual = reqwest::get(info["download_url"].as_str().unwrap()).await.unwrap().bytes().await.unwrap();
    assert_eq!(actual.as_ref(),bytes.as_slice());
    let finished = body_json(app.clone().oneshot(post_json_request(&uri,ip,&token,json!({}))).await.unwrap()).await;
    assert_eq!(finished["complete"],true);
    let key = intent["object_key"].as_str().unwrap();
    state.s3.delete_object(key).await.unwrap();
}

#[tokio::test]
async fn create_attachment_requires_auth() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let response = app
        .oneshot(Request::builder().method("POST").uri("/attachments").header("content-type", "application/json").extension(ip).body(Body::from(json!({"client_attachment_id": Uuid::new_v4(), "encrypted_size": 100}).to_string())).unwrap())
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn create_attachment_returns_upload_intent() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _, _) = register_user(&app, ip, "att").await;

    let body = create_attachment(&app, ip, &token, 1024).await;
    assert!(body["attachment_id"].as_str().is_some());
    assert!(body["object_key"].as_str().unwrap().starts_with("attachments/"));
    assert!(body["upload_url"].as_str().unwrap().contains("X-Amz-Signature="));
}

#[tokio::test]
async fn create_attachment_is_idempotent_on_client_attachment_id() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _, _) = register_user(&app, ip, "att").await;
    let client_attachment_id = Uuid::new_v4();

    let make = |ca_id: Uuid| {
        json!({ "client_attachment_id": ca_id, "encrypted_size": 500 })
    };
    let r1 = app.clone().oneshot(post_json_request("/attachments", ip, &token, make(client_attachment_id))).await.unwrap();
    let b1 = body_json(r1).await;
    let r2 = app.clone().oneshot(post_json_request("/attachments", ip, &token, make(client_attachment_id))).await.unwrap();
    let b2 = body_json(r2).await;

    assert_eq!(b1["attachment_id"], b2["attachment_id"]);
    assert_eq!(b1["object_key"], b2["object_key"]);
}

#[tokio::test]
async fn create_attachment_rejects_invalid_size() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _, _) = register_user(&app, ip, "att").await;

    let response = app
        .oneshot(post_json_request("/attachments", ip, &token, json!({ "client_attachment_id": Uuid::new_v4(), "encrypted_size": 0 })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn create_attachment_rejects_over_max_size() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _, _) = register_user(&app, ip, "att").await;

    let response = app
        .oneshot(post_json_request(
            "/attachments",
            ip,
            &token,
            json!({ "client_attachment_id": Uuid::new_v4(), "encrypted_size": 200 * 1024 * 1024 }),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn object_key_is_opaque_and_random() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, user_id, _) = register_user(&app, ip, "att").await;

    let body = create_attachment(&app, ip, &token, 100).await;
    let object_key = body["object_key"].as_str().unwrap();
    assert!(!object_key.contains(&user_id));
    // The only non-random part is the fixed "attachments/" prefix; the rest
    // must be an opaque UUID, never anything derived from user/chat/filename.
    let suffix = object_key.strip_prefix("attachments/").expect("expected the fixed opaque prefix");
    assert!(Uuid::parse_str(suffix).is_ok(), "object key suffix must be a random UUID, got {suffix}");
}

#[tokio::test]
async fn cannot_complete_someone_elses_attachment() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _, _) = register_user(&app, ip, "att").await;
    let (token_b, _, _) = register_user(&app, ip, "att").await;

    let intent = create_attachment(&app, ip, &token_a, 100).await;
    let response = complete_attachment(&app, ip, &token_b, intent["attachment_id"].as_str().unwrap()).await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn complete_fails_if_object_never_uploaded() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _, _) = register_user(&app, ip, "att").await;

    let intent = create_attachment(&app, ip, &token, 100).await;
    let response = complete_attachment(&app, ip, &token, intent["attachment_id"].as_str().unwrap()).await;
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn complete_fails_if_uploaded_size_mismatches_declared_size() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _, _) = register_user(&app, ip, "att").await;

    let intent = create_attachment(&app, ip, &token, 500).await;
    assert!(!reqwest::Client::new().put(intent["upload_url"].as_str().unwrap()).body(vec![1u8; 10]).send().await.unwrap().status().is_success()); // rejected before storage accepts excess bytes
    let response = complete_attachment(&app, ip, &token, intent["attachment_id"].as_str().unwrap()).await;
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn complete_succeeds_with_correct_size() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _, _) = register_user(&app, ip, "att").await;
    let attachment_id = upload_and_complete(&app, ip, &token, 256).await;
    assert!(!attachment_id.is_empty());
}

// ---------------------------------------------------------------------------
// Download authorization state machine
// ---------------------------------------------------------------------------

#[tokio::test]
async fn uploader_can_download_before_attach() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _, _) = register_user(&app, ip, "att").await;
    let attachment_id = upload_and_complete(&app, ip, &token, 64).await;

    let response = download_attachment(&app, ip, &token, &attachment_id).await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert!(body["download_url"].as_str().unwrap().contains("X-Amz-Signature="));
}

#[tokio::test]
async fn non_uploader_cannot_download_before_attach() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _, _) = register_user(&app, ip, "att").await;
    let (token_b, _, _) = register_user(&app, ip, "att").await;
    let attachment_id = upload_and_complete(&app, ip, &token_a, 64).await;

    let response = download_attachment(&app, ip, &token_b, &attachment_id).await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn chat_member_can_download_after_attach_third_party_cannot() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _, device_a) = register_user(&app, ip, "att").await;
    let (token_b, user_b, device_b) = register_user(&app, ip, "att").await;
    let (token_c, _, _) = register_user(&app, ip, "att").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &user_b).await;

    let attachment_id = upload_and_complete(&app, ip, &token_a, 64).await;

    // Not yet attached to a message - even the future recipient can't get it.
    let pre = download_attachment(&app, ip, &token_b, &attachment_id).await;
    assert_eq!(pre.status(), StatusCode::NOT_FOUND);

    let send_body = json!({
        "client_message_id": Uuid::new_v4(),
        "envelopes": [envelope(&device_a, "hi"), envelope(&device_b, "hi")],
        "attachment_ids": [attachment_id],
    });
    let sent = app.clone().oneshot(post_json_request(&format!("/chats/{chat_id}/messages"), ip, &token_a, send_body)).await.unwrap();
    assert_eq!(sent.status(), StatusCode::OK);

    let after_b = download_attachment(&app, ip, &token_b, &attachment_id).await;
    assert_eq!(after_b.status(), StatusCode::OK);
    let after_c = download_attachment(&app, ip, &token_c, &attachment_id).await;
    assert_eq!(after_c.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn revoked_session_cannot_use_attachment_endpoints() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _, _) = register_user(&app, ip, "att").await;
    let logout = app.clone().oneshot(post_json_request("/auth/logout", ip, &token, json!({}))).await.unwrap();
    assert_eq!(logout.status(), StatusCode::NO_CONTENT);

    let response = app.oneshot(post_json_request("/attachments", ip, &token, json!({ "client_attachment_id": Uuid::new_v4(), "encrypted_size": 10 }))).await.unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

// ---------------------------------------------------------------------------
// Association / message send
// ---------------------------------------------------------------------------

#[tokio::test]
async fn uncompleted_attachment_cannot_be_attached() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _, device_a) = register_user(&app, ip, "att").await;
    let (token_b, user_b, _) = register_user(&app, ip, "att").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &user_b).await;

    let intent = create_attachment(&app, ip, &token_a, 64).await; // never uploaded/completed
    let send_body = json!({
        "client_message_id": Uuid::new_v4(),
        "envelopes": [envelope(&device_a, "hi")],
        "attachment_ids": [intent["attachment_id"]],
    });
    let response = app.oneshot(post_json_request(&format!("/chats/{chat_id}/messages"), ip, &token_a, send_body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn attachment_owned_by_another_user_cannot_be_attached() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _, device_a) = register_user(&app, ip, "att").await;
    let (token_b, user_b, _) = register_user(&app, ip, "att").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &user_b).await;

    let attachment_id = upload_and_complete(&app, ip, &token_b, 64).await; // uploaded by B
    let send_body = json!({
        "client_message_id": Uuid::new_v4(),
        "envelopes": [envelope(&device_a, "hi")],
        "attachment_ids": [attachment_id],
    });
    // A tries to send a message referencing B's attachment.
    let response = app.oneshot(post_json_request(&format!("/chats/{chat_id}/messages"), ip, &token_a, send_body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn same_attachment_cannot_attach_to_a_second_unrelated_message() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _, device_a) = register_user(&app, ip, "att").await;
    let (token_b, user_b, _) = register_user(&app, ip, "att").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &user_b).await;

    let attachment_id = upload_and_complete(&app, ip, &token_a, 64).await;
    let first = json!({ "client_message_id": Uuid::new_v4(), "envelopes": [envelope(&device_a, "hi")], "attachment_ids": [attachment_id.clone()] });
    let r1 = app.clone().oneshot(post_json_request(&format!("/chats/{chat_id}/messages"), ip, &token_a, first)).await.unwrap();
    assert_eq!(r1.status(), StatusCode::OK);

    let second = json!({ "client_message_id": Uuid::new_v4(), "envelopes": [envelope(&device_a, "again")], "attachment_ids": [attachment_id] });
    let r2 = app.oneshot(post_json_request(&format!("/chats/{chat_id}/messages"), ip, &token_a, second)).await.unwrap();
    assert_eq!(r2.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn retried_message_send_with_same_attachment_is_idempotent_not_an_error() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _, device_a) = register_user(&app, ip, "att").await;
    let (token_b, user_b, _) = register_user(&app, ip, "att").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &user_b).await;

    let attachment_id = upload_and_complete(&app, ip, &token_a, 64).await;
    let client_message_id = Uuid::new_v4();
    let body = || json!({ "client_message_id": client_message_id, "envelopes": [envelope(&device_a, "hi")], "attachment_ids": [attachment_id.clone()] });

    let r1 = app.clone().oneshot(post_json_request(&format!("/chats/{chat_id}/messages"), ip, &token_a, body())).await.unwrap();
    assert_eq!(r1.status(), StatusCode::OK);
    let b1 = body_json(r1).await;

    // Simulated retry after a lost response: same client_message_id, same attachment.
    let r2 = app.oneshot(post_json_request(&format!("/chats/{chat_id}/messages"), ip, &token_a, body())).await.unwrap();
    assert_eq!(r2.status(), StatusCode::OK);
    let b2 = body_json(r2).await;
    assert_eq!(b1["id"], b2["id"]);
}

// ---------------------------------------------------------------------------
// Concurrency
// ---------------------------------------------------------------------------

#[tokio::test]
async fn concurrent_create_with_same_client_attachment_id_yields_one_attachment() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _, _) = register_user(&app, ip, "att").await;
    let client_attachment_id = Uuid::new_v4();

    let mut handles = Vec::new();
    for _ in 0..10 {
        let app = app.clone();
        let token = token.clone();
        handles.push(tokio::spawn(async move {
            app.oneshot(post_json_request("/attachments", ip, &token, json!({ "client_attachment_id": client_attachment_id, "encrypted_size": 42 }))).await.unwrap()
        }));
    }
    let mut ids = std::collections::HashSet::new();
    for handle in handles {
        let response = handle.await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        ids.insert(body_json(response).await["attachment_id"].as_str().unwrap().to_string());
    }
    assert_eq!(ids.len(), 1);
}

// ---------------------------------------------------------------------------
// Cleanup
// ---------------------------------------------------------------------------

#[tokio::test]
async fn orphan_sweep_deletes_old_unattached_and_keeps_attached_and_recent() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();

    // N.2R test isolation: the shared test DB accumulates unattached rows from
    // every prior attachment test run. Clear the orphan pool up front so the
    // sweep count below is deterministic (only this test's own rows remain).
    // Attached rows are left untouched (they are the FK-referenced source of
    // truth and must never be swept).
    sqlx::query("DELETE FROM attachments WHERE status <> 'attached'")
        .execute(&state.db)
        .await
        .unwrap();

    let (token_a, _, device_a) = register_user(&app, ip, "att").await;
    let (token_b, user_b, _) = register_user(&app, ip, "att").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &user_b).await;

    let attached_id = upload_and_complete(&app, ip, &token_a, 32).await;
    let send_body = json!({ "client_message_id": Uuid::new_v4(), "envelopes": [envelope(&device_a, "hi")], "attachment_ids": [attached_id.clone()] });
    let sent = app.clone().oneshot(post_json_request(&format!("/chats/{chat_id}/messages"), ip, &token_a, send_body)).await.unwrap();
    assert_eq!(sent.status(), StatusCode::OK);

    let recent_orphan = upload_and_complete(&app, ip, &token_a, 32).await;

    // Backdate an old, never-attached attachment directly (simulates a real
    // 24h-old orphan without waiting 24h in a test).
    let old_orphan = upload_and_complete(&app, ip, &token_a, 32).await;
    sqlx::query("UPDATE attachments SET created_at = now() - interval '48 hours', upload_lease_until=now()-interval '1 minute' WHERE id = $1")
        .bind(Uuid::parse_str(&old_orphan).unwrap())
        .execute(&state.db)
        .await
        .unwrap();

    let swept = messenger_server::attachments::cleanup::sweep_orphan_attachments(&state).await.unwrap();
    assert_eq!(swept, 1);

    let remaining: Vec<Uuid> = sqlx::query_scalar("SELECT id FROM attachments WHERE id = ANY($1)")
        .bind([Uuid::parse_str(&attached_id).unwrap(), Uuid::parse_str(&recent_orphan).unwrap(), Uuid::parse_str(&old_orphan).unwrap()])
        .fetch_all(&state.db)
        .await
        .unwrap();
    assert!(remaining.contains(&Uuid::parse_str(&attached_id).unwrap()));
    assert!(remaining.contains(&Uuid::parse_str(&recent_orphan).unwrap()));
    assert!(!remaining.contains(&Uuid::parse_str(&old_orphan).unwrap()));
}
