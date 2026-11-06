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
    let _ = tracing_subscriber::fmt().with_test_writer().try_init();
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

fn b64(bytes: &[u8]) -> String {
    STANDARD.encode(bytes)
}

/// Registers a fresh user/device and returns (access_token, user_id, device_id).
async fn register_user(app: &axum::Router, ip: ConnectInfo<SocketAddr>, prefix: &str) -> (String, String, String) {
    let username = unique_username(prefix);
    let response = app.clone().oneshot(register_request(ip, &username, "Test User")).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        body["device_id"].as_str().unwrap().to_string(),
    )
}

fn upload_keys_body(one_time_key_ids: &[i32]) -> Value {
    json!({
        "identity_key": b64(b"fake-identity-key-bytes"),
        "registration_id": 12345,
        "signed_prekey": { "key_id": 1, "public_key": b64(b"fake-signed-prekey"), "signature": b64(b"fake-signature") },
        "kyber_prekey": { "key_id": 1, "public_key": b64(b"fake-kyber-prekey"), "signature": b64(b"fake-kyber-signature") },
        "one_time_prekeys": one_time_key_ids.iter().map(|id| json!({
            "key_id": id, "public_key": b64(format!("fake-otp-{id}").as_bytes()),
        })).collect::<Vec<_>>(),
    })
}

#[tokio::test]
async fn upload_keys_succeeds_and_reports_count() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _id, _device) = register_user(&app, ip, "up_a_").await;

    let response = app.oneshot(post_json_request("/keys", ip, &token, upload_keys_body(&[1, 2, 3]))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["one_time_prekey_count"], 3);
}

fn upload_keys_body_with_identity(identity_key_b64: &str, one_time_key_ids: &[i32]) -> Value {
    json!({
        "identity_key": identity_key_b64,
        "registration_id": 12345,
        "signed_prekey": { "key_id": 1, "public_key": b64(b"fake-signed-prekey"), "signature": b64(b"fake-signature") },
        "kyber_prekey": { "key_id": 1, "public_key": b64(b"fake-kyber-prekey"), "signature": b64(b"fake-kyber-signature") },
        "one_time_prekeys": one_time_key_ids.iter().map(|id| json!({
            "key_id": id, "public_key": b64(format!("fake-otp-{id}").as_bytes()),
        })).collect::<Vec<_>>(),
    })
}

#[tokio::test]
async fn identity_rotation_clears_stale_one_time_prekeys() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token, _id, device_id) = register_user(&app, ip, "rotk_a_").await;

    app.clone()
        .oneshot(post_json_request("/keys", ip, &token, upload_keys_body_with_identity(&b64(b"identity-v1"), &[1, 2, 3])))
        .await
        .unwrap();

    // Same device re-installs: a NEW identity key, with fresh one-time prekeys.
    let response = app
        .oneshot(post_json_request("/keys", ip, &token, upload_keys_body_with_identity(&b64(b"identity-v2"), &[4, 5])))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let device_uuid = Uuid::parse_str(&device_id).unwrap();
    let (stale_count,): (i64,) = sqlx::query_as(
        "SELECT COUNT(*) FROM one_time_prekeys WHERE device_id = $1 AND key_id IN (1, 2, 3)",
    )
    .bind(device_uuid)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(stale_count, 0, "identity rotation must delete stale one-time prekeys the device no longer holds");

    let (fresh_count,): (i64,) = sqlx::query_as(
        "SELECT COUNT(*) FROM one_time_prekeys WHERE device_id = $1 AND key_id IN (4, 5)",
    )
    .bind(device_uuid)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(fresh_count, 2, "fresh one-time prekeys must survive the identity rotation upload");
}

#[tokio::test]
async fn reupload_replaces_identity_and_signed_prekey() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token, _id, device_id) = register_user(&app, ip, "re_a_").await;

    app.clone().oneshot(post_json_request("/keys", ip, &token, upload_keys_body(&[1]))).await.unwrap();
    let response = app.oneshot(post_json_request("/keys", ip, &token, upload_keys_body(&[2, 3]))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let (count,): (i64,) = sqlx::query_as("SELECT COUNT(*) FROM device_identity_keys WHERE device_id = $1")
        .bind(Uuid::parse_str(&device_id).unwrap())
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(count, 1, "re-upload must replace, not duplicate, the identity key row");
}

#[tokio::test]
async fn bundle_fetch_returns_device_material_and_consumes_one_time_key() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, id_a, _device_a) = register_user(&app, ip, "bun_a_").await;
    let (_token_b, id_b, device_b) = register_user(&app, ip, "bun_b_").await;

    app.clone().oneshot(post_json_request("/keys", ip, &_token_b, upload_keys_body(&[10]))).await.unwrap();

    let uri = format!("/keys/bundles/{id_b}");
    let response = app.oneshot(get_request(&uri, ip, Some(&token_a))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    let devices = body["devices"].as_array().unwrap();
    assert_eq!(devices.len(), 1);
    let bundle = &devices[0];
    assert_eq!(bundle["device_id"], device_b);
    assert!(bundle["signal_device_id"].as_i64().is_some());
    assert_eq!(bundle["one_time_prekey_id"], 10);

    let (used,): (bool,) = sqlx::query_as(
        "SELECT used_at IS NOT NULL FROM one_time_prekeys WHERE device_id = $1 AND key_id = 10",
    )
    .bind(Uuid::parse_str(&device_b).unwrap())
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert!(used, "the handed-out one-time prekey must be marked used");
    let _ = id_a;
}

#[tokio::test]
async fn a_requester_exhausting_its_own_budget_does_not_block_another_requester() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _device_a) = register_user(&app, ip, "fair_a_").await;
    let (token_b, _id_b, _device_b) = register_user(&app, ip, "fair_b_").await;
    let (_token_c, id_c, _device_c) = register_user(&app, ip, "fair_c_").await;

    app.clone()
        .oneshot(post_json_request("/keys", ip, &_token_c, upload_keys_body(&(1..=20).collect::<Vec<_>>())))
        .await
        .unwrap();

    let uri = format!("/keys/bundles/{id_c}");
    for _ in 0..10 {
        let response = app.clone().oneshot(get_request(&uri, ip, Some(&token_a))).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
    }
    let blocked = app.clone().oneshot(get_request(&uri, ip, Some(&token_a))).await.unwrap();
    assert_eq!(blocked.status(), StatusCode::TOO_MANY_REQUESTS, "A must be limited by its own pair budget");

    let response = app.oneshot(get_request(&uri, ip, Some(&token_b))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK, "B must still be able to discover C's keys");
    let body = body_json(response).await;
    let bundle = &body["devices"].as_array().unwrap()[0];
    assert!(bundle["one_time_prekey_id"].as_i64().is_some(), "B must receive a one-time key while the shared budget is healthy");
}

#[tokio::test]
async fn target_prekey_budget_degrades_to_fallback_instead_of_blocking_bundles() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (_token_c, id_c, _device_c) = register_user(&app, ip, "budget_c_").await;
    app.clone()
        .oneshot(post_json_request("/keys", ip, &_token_c, upload_keys_body(&(1..=40).collect::<Vec<_>>())))
        .await
        .unwrap();

    let mut tokens = Vec::new();
    for prefix in ["budget_a1_", "budget_a2_", "budget_a3_", "budget_a4_"] {
        let (token, _, _) = register_user(&app, ip, prefix).await;
        tokens.push(token);
    }
    let uri = format!("/keys/bundles/{id_c}");
    // Three requesters consume 10 each, exhausting the target-wide budget of 30.
    for token in &tokens[..3] {
        for _ in 0..10 {
            let response = app.clone().oneshot(get_request(&uri, ip, Some(token))).await.unwrap();
            assert_eq!(response.status(), StatusCode::OK);
        }
    }
    let fallback = app.oneshot(get_request(&uri, ip, Some(&tokens[3]))).await.unwrap();
    assert_eq!(fallback.status(), StatusCode::OK, "budget exhaustion must not deny key discovery");
    let body = body_json(fallback).await;
    let bundle = &body["devices"].as_array().unwrap()[0];
    assert!(bundle["one_time_prekey_id"].is_null(), "fallback bundle deliberately omits a one-time prekey");
    assert!(bundle["signed_prekey_id"].as_i64().is_some());
}

#[tokio::test]
async fn bundle_fetch_omits_one_time_key_when_pool_exhausted() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a) = register_user(&app, ip, "empty_a_").await;
    let (token_b, id_b, _device_b) = register_user(&app, ip, "empty_b_").await;

    // No one-time prekeys uploaded, only the required singleton keys.
    app.clone().oneshot(post_json_request("/keys", ip, &token_b, upload_keys_body(&[]))).await.unwrap();

    let uri = format!("/keys/bundles/{id_b}");
    let response = app.oneshot(get_request(&uri, ip, Some(&token_a))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    let bundle = &body["devices"].as_array().unwrap()[0];
    assert!(bundle["one_time_prekey_id"].is_null());
    assert!(bundle["one_time_prekey_public"].is_null());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 8)]
async fn concurrent_bundle_fetches_never_hand_out_the_same_one_time_key_twice() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, _id_a, _device_a) = register_user(&app, ip, "conc_a_").await;
    let (token_b, id_b, _device_b) = register_user(&app, ip, "conc_b_").await;

    app.clone()
        .oneshot(post_json_request("/keys", ip, &token_b, upload_keys_body(&(1..=10).collect::<Vec<_>>())))
        .await
        .unwrap();

    let mut handles = Vec::new();
    for _ in 0..10 {
        let app = app.clone();
        let token = token_a.clone();
        let uri = format!("/keys/bundles/{id_b}");
        handles.push(tokio::spawn(async move { app.oneshot(get_request(&uri, ip, Some(&token))).await.unwrap() }));
    }

    let mut seen_key_ids = std::collections::HashSet::new();
    for handle in handles {
        let response = handle.await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = body_json(response).await;
        let bundle = &body["devices"].as_array().unwrap()[0];
        let key_id = bundle["one_time_prekey_id"].as_i64().unwrap();
        assert!(seen_key_ids.insert(key_id), "the same one-time prekey id was handed out twice: {key_id}");
    }
    assert_eq!(seen_key_ids.len(), 10);
}

#[tokio::test]
async fn bundle_fetch_skips_devices_with_no_uploaded_keys() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _device_a) = register_user(&app, ip, "nokeys_a_").await;
    let (_token_b, id_b, _device_b) = register_user(&app, ip, "nokeys_b_").await;
    // B never uploads any keys.

    let uri = format!("/keys/bundles/{id_b}");
    let response = app.oneshot(get_request(&uri, ip, Some(&token_a))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert!(body["devices"].as_array().unwrap().is_empty());
}

#[tokio::test]
async fn one_time_prekey_count_reflects_remaining_pool() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _id, _device) = register_user(&app, ip, "cnt_a_").await;

    app.clone().oneshot(post_json_request("/keys", ip, &token, upload_keys_body(&[1, 2]))).await.unwrap();
    let response = app.oneshot(get_request("/keys/one-time-count", ip, Some(&token))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["count"], 2);
}

#[tokio::test]
async fn upload_keys_requires_authentication() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    let request = Request::builder()
        .method("POST")
        .uri("/keys")
        .header("content-type", "application/json")
        .extension(ip)
        .body(Body::from(upload_keys_body(&[1]).to_string()))
        .unwrap();
    let response = app.oneshot(request).await.unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn upload_keys_rejects_invalid_base64() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token, _id, _device) = register_user(&app, ip, "badb64_").await;

    let mut body = upload_keys_body(&[1]);
    body["identity_key"] = json!("not-valid-base64!!!");
    let response = app.oneshot(post_json_request("/keys", ip, &token, body)).await.unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}
