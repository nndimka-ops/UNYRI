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

fn sync_uri(after_sequence: Option<i64>, limit: Option<i64>) -> String {
    let mut params = Vec::new();
    if let Some(a) = after_sequence {
        params.push(format!("after_sequence={a}"));
    }
    if let Some(l) = limit {
        params.push(format!("limit={l}"));
    }
    if params.is_empty() {
        "/sync".to_string()
    } else {
        format!("/sync?{}", params.join("&"))
    }
}

#[tokio::test]
async fn sync_requires_authentication() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    let response = app.oneshot(get_request(&sync_uri(None, None), ip, None)).await.unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn sync_revoked_session_returns_unauthorized() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _id, _device, _u) = register_user(&app, ip, "syncrev_").await;

    let logout = app.clone().oneshot(post_request("/auth/logout", ip, &token)).await.unwrap();
    assert_eq!(logout.status(), StatusCode::NO_CONTENT);

    let response = app.oneshot(get_request(&sync_uri(None, None), ip, Some(&token))).await.unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn sync_is_empty_for_a_fresh_device_with_nothing_new() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _id, _device, _u) = register_user(&app, ip, "syncempty_").await;

    let response = app.oneshot(get_request(&sync_uri(None, None), ip, Some(&token))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert!(body["messages"].as_array().unwrap().is_empty());
    assert_eq!(body["has_more"], false);
    assert_eq!(body["next_sequence"], 0);
}

#[tokio::test]
async fn sync_after_sequence_filters_and_orders_ascending() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "syncord_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "syncord_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    let mut sequences = Vec::new();
    for text in ["one", "two", "three"] {
        sequences.push(send_message(&app, ip, &token_a, &chat_id, &device_b, text).await);
    }

    // From the very start: all three, ascending.
    let response = app.clone().oneshot(get_request(&sync_uri(Some(0), None), ip, Some(&_token_b))).await.unwrap();
    let body = body_json(response).await;
    let messages = body["messages"].as_array().unwrap();
    assert_eq!(messages.len(), 3);
    let returned_sequences: Vec<i64> = messages.iter().map(|m| m["sequence"].as_i64().unwrap()).collect();
    assert_eq!(returned_sequences, sequences);
    assert_eq!(body["next_sequence"], sequences[2]);

    // From after the first message: only the last two.
    let response =
        app.oneshot(get_request(&sync_uri(Some(sequences[0]), None), ip, Some(&_token_b))).await.unwrap();
    let body = body_json(response).await;
    let messages = body["messages"].as_array().unwrap();
    assert_eq!(messages.len(), 2);
    assert_eq!(messages[0]["sequence"], sequences[1]);
    assert_eq!(messages[1]["sequence"], sequences[2]);
}

#[tokio::test]
async fn sync_paginates_with_has_more() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "syncpage_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "syncpage_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    for i in 0..12 {
        send_message(&app, ip, &token_a, &chat_id, &device_b, &format!("m{i}")).await;
    }

    let mut seen = std::collections::HashSet::new();
    let mut after: i64 = 0;
    loop {
        let response = app.clone().oneshot(get_request(&sync_uri(Some(after), Some(5)), ip, Some(&token_b))).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = body_json(response).await;
        let messages = body["messages"].as_array().unwrap().clone();
        assert!(messages.len() <= 5);
        for m in &messages {
            assert!(seen.insert(m["id"].as_str().unwrap().to_string()), "duplicate message across sync pages");
        }
        after = body["next_sequence"].as_i64().unwrap();
        if !body["has_more"].as_bool().unwrap() {
            break;
        }
    }
    assert_eq!(seen.len(), 12, "all messages must be retrievable across sync pages with no duplicates");
}

#[tokio::test]
async fn sync_high_cursor_returns_empty_and_unchanged_cursor() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "synchigh_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "synchigh_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;
    let last_seq = send_message(&app, ip, &token_a, &chat_id, &device_b, "hi").await;

    let response =
        app.oneshot(get_request(&sync_uri(Some(last_seq), None), ip, Some(&token_b))).await.unwrap();
    let body = body_json(response).await;
    assert!(body["messages"].as_array().unwrap().is_empty());
    assert_eq!(body["has_more"], false);
    assert_eq!(body["next_sequence"], last_seq);
}

#[tokio::test]
async fn sync_repeated_request_returns_stable_data() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "syncstable_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "syncstable_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;
    send_message(&app, ip, &token_a, &chat_id, &device_b, "hello").await;

    let first = app.clone().oneshot(get_request(&sync_uri(Some(0), None), ip, Some(&token_b))).await.unwrap();
    let first_body = body_json(first).await;
    let second = app.oneshot(get_request(&sync_uri(Some(0), None), ip, Some(&token_b))).await.unwrap();
    let second_body = body_json(second).await;
    assert_eq!(first_body, second_body, "repeating the same sync request must return identical data");
}

#[tokio::test]
async fn sync_gaps_from_other_users_traffic_are_not_an_error() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "syncgap_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "syncgap_b_").await;
    let (token_c, _id_c, _device_c, _u_c) = register_user(&app, ip, "syncgap_c_").await;
    let (_token_d, id_d, device_d, _u_d) = register_user(&app, ip, "syncgap_d_").await;

    let chat_ab = create_direct_chat(&app, ip, &token_a, &id_b).await;
    let chat_cd = create_direct_chat(&app, ip, &token_c, &id_d).await;

    // Interleave: A->B, C->D, A->B - B's global sequence view will have a
    // gap where C->D's message sits, which is normal, not data loss.
    send_message(&app, ip, &token_a, &chat_ab, &device_b, "first").await;
    send_message(&app, ip, &token_c, &chat_cd, &device_d, "unrelated").await;
    let last = send_message(&app, ip, &token_a, &chat_ab, &device_b, "second").await;

    let response = app.oneshot(get_request(&sync_uri(Some(0), None), ip, Some(&token_b))).await.unwrap();
    let body = body_json(response).await;
    let messages = body["messages"].as_array().unwrap();
    assert_eq!(messages.len(), 2, "B must see exactly its own two messages, gap included but not treated as an error");
    assert_eq!(messages[1]["sequence"], last);
}

#[tokio::test]
async fn sync_never_leaks_another_users_messages() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "syncleak_a_").await;
    let (_token_b, id_b, device_b, _u_b) = register_user(&app, ip, "syncleak_b_").await;
    let (token_c, _id_c, _device_c, _u_c) = register_user(&app, ip, "syncleak_c_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;
    send_message(&app, ip, &token_a, &chat_id, &device_b, "not for C").await;

    let response = app.oneshot(get_request(&sync_uri(Some(0), None), ip, Some(&token_c))).await.unwrap();
    let body = body_json(response).await;
    assert!(body["messages"].as_array().unwrap().is_empty(), "an unrelated user must never see another user's messages via sync");
}

#[tokio::test]
async fn sync_is_scoped_to_the_current_device_only() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "syncdev_a_").await;
    let (token_b1, id_b, device_b1, username_b) = register_user(&app, ip, "syncdev_b_").await;
    let (token_b2, device_b2) = login_new_device(&app, ip, &username_b).await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;

    // Addressed ONLY to device_b1's envelope - device_b2 must not see it.
    send_message(&app, ip, &token_a, &chat_id, &device_b1, "only for device 1").await;

    let response_b1 = app.clone().oneshot(get_request(&sync_uri(Some(0), None), ip, Some(&token_b1))).await.unwrap();
    let body_b1 = body_json(response_b1).await;
    assert_eq!(body_b1["messages"].as_array().unwrap().len(), 1, "device 1 must see the envelope addressed to it");

    let response_b2 = app.oneshot(get_request(&sync_uri(Some(0), None), ip, Some(&token_b2))).await.unwrap();
    let body_b2 = body_json(response_b2).await;
    assert!(body_b2["messages"].as_array().unwrap().is_empty(), "device 2 must never see device 1's envelope via sync");
    let _ = device_b2;
}

#[tokio::test]
async fn sync_invalid_limit_is_clamped_not_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _id, _device, _u) = register_user(&app, ip, "synclimit_").await;

    let too_low = app.clone().oneshot(get_request(&sync_uri(Some(0), Some(-5)), ip, Some(&token))).await.unwrap();
    assert_eq!(too_low.status(), StatusCode::OK);

    let too_high = app.oneshot(get_request(&sync_uri(Some(0), Some(1_000_000)), ip, Some(&token))).await.unwrap();
    assert_eq!(too_high.status(), StatusCode::OK);
}

#[tokio::test]
async fn sync_marks_delivered_as_a_side_effect() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _device_a, _u_a) = register_user(&app, ip, "syncdeliv_a_").await;
    let (token_b, id_b, device_b, _u_b) = register_user(&app, ip, "syncdeliv_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;
    send_message(&app, ip, &token_a, &chat_id, &device_b, "hi").await;

    app.oneshot(get_request(&sync_uri(Some(0), None), ip, Some(&token_b))).await.unwrap();

    let (delivered,): (bool,) = sqlx::query_as(
        "SELECT delivered_at IS NOT NULL FROM message_receipts mr JOIN messages m ON m.id = mr.message_id \
         WHERE m.chat_id = $1 AND mr.user_id = $2",
    )
    .bind(Uuid::parse_str(&chat_id).unwrap())
    .bind(Uuid::parse_str(&id_b).unwrap())
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert!(delivered, "fetching via /sync must mark the message delivered for this recipient");
}

#[tokio::test]
async fn history_clear_marker_survives_empty_and_repeated_sync() {
    let state = spawn_state().await;
    let old: Option<(i64,i64)> = sqlx::query_as("SELECT before_ms,through_sequence FROM history_clear_state WHERE singleton=TRUE").fetch_optional(&state.db).await.unwrap();
    sqlx::query("INSERT INTO history_clear_state(singleton,before_ms,through_sequence) VALUES(TRUE,123456,42) ON CONFLICT(singleton) DO UPDATE SET before_ms=excluded.before_ms,through_sequence=excluded.through_sequence").execute(&state.db).await.unwrap();
    let app = messenger_server::app(state.clone());
    let ip = ConnectInfo("127.0.7.99:5999".parse::<SocketAddr>().unwrap());
    let (token, _, _, _) = register_user(&app, ip, "historyclear").await;
    for cursor in [0, i64::MAX] {
        let response = app.clone().oneshot(get_request(&sync_uri(Some(cursor), None), ip, Some(&token))).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = body_json(response).await;
        assert_eq!(body["history_cleared_before_ms"], 123456);
        assert_eq!(body["history_cleared_through_sequence"], 42);
        assert!(body["messages"].as_array().unwrap().is_empty());
        assert_eq!(body["next_sequence"], cursor);
    }
    if let Some(value) = old { sqlx::query("UPDATE history_clear_state SET before_ms=$1,through_sequence=$2").bind(value.0).bind(value.1).execute(&state.db).await.unwrap(); }
    else { sqlx::query("DELETE FROM history_clear_state").execute(&state.db).await.unwrap(); }
}
