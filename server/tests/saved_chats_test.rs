use std::net::SocketAddr;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode};
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
        .max_connections(10)
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
        database_max_connections: 10,
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

fn register_request(ip: ConnectInfo<SocketAddr>, username: &str, device_id: Uuid) -> Request<Body> {
    let body = json!({
        "username": username,
        "password": "correct-horse-battery",
        "display_name": "Test User",
        "installation_id": device_id,
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

fn login_request(ip: ConnectInfo<SocketAddr>, username: &str, device_id: Uuid) -> Request<Body> {
    let body = json!({
        "username": username,
        "password": "correct-horse-battery",
        "installation_id": device_id,
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

/// Registers a fresh user and returns (access_token, user_id, device_id).
async fn register_user(app: &axum::Router, ip: ConnectInfo<SocketAddr>, prefix: &str) -> (String, String, Uuid) {
    let username = unique_username(prefix);
    let device_id = Uuid::new_v4();
    let response = app.clone().oneshot(register_request(ip, &username, device_id)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        Uuid::parse_str(body["device_id"].as_str().unwrap()).unwrap(),
    )
}

/// Logs in a second device for an existing user; returns (token, device_id).
async fn login_second_device(
    app: &axum::Router,
    ip: ConnectInfo<SocketAddr>,
    username: &str,
) -> (String, Uuid) {
    let device_id = Uuid::new_v4();
    let response = app.clone().oneshot(login_request(ip, username, device_id)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        Uuid::parse_str(body["device_id"].as_str().unwrap()).unwrap(),
    )
}

/// Uploads minimal (arbitrary-byte) Signal key material for a device, so the
/// strict multi-device coverage check treats it as a real keyed device. A
/// keyless device (never uploaded keys) is skipped by the bundle fetch and
/// therefore can never be encrypted to - the N.2R fix excludes such devices
/// from the coverage requirement, which these tests model by uploading keys.
async fn upload_keys_for_device(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str) {
    let response = app
        .clone()
        .oneshot(post_json_request(
            "/keys",
            ip,
            token,
            json!({
                "identity_key": "aWQ=",
                "registration_id": 12345,
                "signed_prekey": { "key_id": 1, "public_key": "c3Br", "signature": "c2ln" },
                "kyber_prekey": { "key_id": 1, "public_key": "a3li", "signature": "a3NpZw==" },
                "one_time_prekeys": [{ "key_id": 1, "public_key": "b3RwMQ==" }],
            }),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn saved_chat_get_or_create_returns_saved_type_with_no_other_user() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "sv_a_").await;

    let response = app.oneshot(get_request("/chats/saved", ip, Some(&token_a))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["type"], "saved");
    assert!(body["other_user"].is_null());
    assert!(body["chat_id"].as_str().is_some());
}

#[tokio::test]
async fn repeated_get_or_create_returns_same_chat() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "svrep_").await;

    let first = app.clone().oneshot(get_request("/chats/saved", ip, Some(&token_a))).await.unwrap();
    let first_body = body_json(first).await;

    let second = app.oneshot(get_request("/chats/saved", ip, Some(&token_a))).await.unwrap();
    let second_body = body_json(second).await;

    assert_eq!(first_body["chat_id"], second_body["chat_id"]);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn concurrent_twenty_get_or_create_yields_exactly_one_row() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, id_a, _dev_a) = register_user(&app, ip, "svcon_").await;

    let mut handles = Vec::new();
    for _ in 0..20 {
        let app = app.clone();
        let token = token_a.clone();
        handles.push(tokio::spawn(async move {
            let response = app.oneshot(get_request("/chats/saved", ip, Some(&token))).await.unwrap();
            assert_eq!(response.status(), StatusCode::OK);
            body_json(response).await["chat_id"].as_str().unwrap().to_string()
        }));
    }

    let mut ids = Vec::new();
    for handle in handles {
        ids.push(handle.await.unwrap());
    }
    ids.dedup();
    assert_eq!(ids.len(), 1, "exactly one saved chat must exist");

    // DB-enforced: exactly one row.
    let pool = sqlx::postgres::PgPoolOptions::new()
        .max_connections(2)
        .connect(&std::env::var("TEST_DATABASE_URL")
            .unwrap_or_else(|_| "postgres://messenger:messenger@localhost:5432/messenger_test".to_string()))
        .await
        .unwrap();
    let owner: Uuid = Uuid::parse_str(&id_a).unwrap();
    let (count,): (i64,) =
        sqlx::query_as("SELECT COUNT(*) FROM chats WHERE owner_user_id = $1 AND type = 'saved'")
            .bind(owner)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(count, 1);
    let (members,): (i64,) =
        sqlx::query_as("SELECT COUNT(*) FROM chat_members cm JOIN chats c ON c.id = cm.chat_id WHERE c.owner_user_id = $1 AND c.type = 'saved'")
            .bind(owner)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(members, 1, "saved chat must have exactly one member");
}

#[tokio::test]
async fn foreign_user_cannot_read_saved_chat() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "svf_a_").await;
    let (token_b, _id_b, _dev_b) = register_user(&app, ip, "svf_b_").await;

    let created = app.clone().oneshot(get_request("/chats/saved", ip, Some(&token_a))).await.unwrap();
    let saved_id = body_json(created).await["chat_id"].as_str().unwrap().to_string();

    // Bob cannot read Alice's saved chat.
    let response = app.oneshot(get_request(&format!("/chats/{saved_id}"), ip, Some(&token_b))).await.unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn direct_chats_are_unaffected_by_saved_chat_semantics() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, id_a, _dev_a) = register_user(&app, ip, "svd_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "svd_b_").await;

    // Saved chat creation must not interfere with direct pair creation.
    app.clone().oneshot(get_request("/chats/saved", ip, Some(&token_a))).await.unwrap();

    let response = app
        .clone()
        .oneshot(post_json_request("/chats/direct", ip, &token_a, json!({ "other_user_id": id_b })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["type"], "direct");
    assert_eq!(body["other_user"]["id"], id_b);

    // And a second direct chat attempt still returns the same direct chat.
    let again = app
        .clone()
        .oneshot(post_json_request("/chats/direct", ip, &token_a, json!({ "other_user_id": id_b })))
        .await
        .unwrap();
    assert_eq!(body_json(again).await["chat_id"], body["chat_id"]);
    let _ = id_a;
}

#[tokio::test]
async fn saved_chat_appears_in_chat_list_with_saved_type() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, _id_a, _dev_a) = register_user(&app, ip, "svl_").await;

    app.clone().oneshot(get_request("/chats/saved", ip, Some(&token_a))).await.unwrap();

    let response = app.oneshot(get_request("/chats?limit=50", ip, Some(&token_a))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    let items = body["items"].as_array().unwrap();
    assert_eq!(items.len(), 1);
    assert_eq!(items[0]["type"], "saved");
    assert!(items[0]["other_user"].is_null());
}

#[tokio::test]
async fn send_to_saved_chat_targets_only_own_active_devices() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    let username = unique_username("svm_owner_");
    let device_a = Uuid::new_v4();
    let reg = app
        .clone()
        .oneshot(register_request(ip, &username, device_a))
        .await
        .unwrap();
    let reg_body = body_json(reg).await;
    let token_owner = reg_body["access_token"].as_str().unwrap().to_string();

    let (_token_second, device_second) = login_second_device(&app, ip, &username).await;

    let saved = app
        .clone()
        .oneshot(get_request("/chats/saved", ip, Some(&token_owner)))
        .await
        .unwrap();
    let saved_id = body_json(saved).await["chat_id"].as_str().unwrap().to_string();

    // Envelope to the owner's own other device must be accepted.
    let response = app
        .clone()
        .oneshot(post_json_request(
            &format!("/chats/{saved_id}/messages"),
            ip,
            &token_owner,
            json!({
                "client_message_id": Uuid::new_v4(),
                "kind": "content",
                "envelopes": [{ "recipient_device_id": device_second, "message_type": 2, "ciphertext": "AA==" }],
            }),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    // A foreign device (not the owner's) is rejected.
    let (token_other, _id_other, _dev_other) = register_user(&app, ip, "svm_oth_").await;
    let _ = token_other;
    let foreign_device = Uuid::new_v4();
    let response = app
        .oneshot(post_json_request(
            &format!("/chats/{saved_id}/messages"),
            ip,
            &token_owner,
            json!({
                "client_message_id": Uuid::new_v4(),
                "kind": "content",
                "envelopes": [{ "recipient_device_id": foreign_device, "message_type": 2, "ciphertext": "AA==" }],
            }),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn single_device_saved_chat_send_with_zero_envelopes_is_accepted() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    // Single-device account: no second device to encrypt for.
    let (token_owner, _id_owner, _dev_owner) = register_user(&app, ip, "svz_").await;

    let saved = app
        .clone()
        .oneshot(get_request("/chats/saved", ip, Some(&token_owner)))
        .await
        .unwrap();
    let saved_id = body_json(saved).await["chat_id"].as_str().unwrap().to_string();

    let response = app
        .oneshot(post_json_request(
            &format!("/chats/{saved_id}/messages"),
            ip,
            &token_owner,
            json!({
                "client_message_id": Uuid::new_v4(),
                "kind": "content",
                "envelopes": [],
            }),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn multi_device_saved_chat_send_with_zero_envelopes_is_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    // Two active devices of the SAME account: A1 sends from one device, but
    // A2 must be covered by an envelope (strict multi-device sync). Zero
    // envelopes must therefore be rejected, unlike the single-device case.
    let username = unique_username("svmz_");
    let device_a = Uuid::new_v4();
    let reg = app
        .clone()
        .oneshot(register_request(ip, &username, device_a))
        .await
        .unwrap();
    let token_owner = body_json(reg).await["access_token"].as_str().unwrap().to_string();

    // The second device must have key material for the strict multi-device
    // coverage rule to apply (a keyless device can't be encrypted to).
    let (token_second, _device_second) = login_second_device(&app, ip, &username).await;
    upload_keys_for_device(&app, ip, &token_second).await;

    let saved = app
        .clone()
        .oneshot(get_request("/chats/saved", ip, Some(&token_owner)))
        .await
        .unwrap();
    let saved_id = body_json(saved).await["chat_id"].as_str().unwrap().to_string();

    let response = app
        .oneshot(post_json_request(
            &format!("/chats/{saved_id}/messages"),
            ip,
            &token_owner,
            json!({
                "client_message_id": Uuid::new_v4(),
                "kind": "content",
                "envelopes": [],
            }),
        ))
        .await
        .unwrap();
    assert_eq!(
        response.status(),
        StatusCode::BAD_REQUEST,
        "a multi-device account must include its other device in the envelope list"
    );
}

#[tokio::test]
async fn multi_device_saved_chat_send_with_own_device_envelope_is_accepted() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    let username = unique_username("svmo_");
    let device_a = Uuid::new_v4();
    let reg = app
        .clone()
        .oneshot(register_request(ip, &username, device_a))
        .await
        .unwrap();
    let token_owner = body_json(reg).await["access_token"].as_str().unwrap().to_string();

    let (token_second, device_second) = login_second_device(&app, ip, &username).await;
    upload_keys_for_device(&app, ip, &token_second).await;

    let saved = app
        .clone()
        .oneshot(get_request("/chats/saved", ip, Some(&token_owner)))
        .await
        .unwrap();
    let saved_id = body_json(saved).await["chat_id"].as_str().unwrap().to_string();

    let response = app
        .oneshot(post_json_request(
            &format!("/chats/{saved_id}/messages"),
            ip,
            &token_owner,
            json!({
                "client_message_id": Uuid::new_v4(),
                "kind": "content",
                "envelopes": [{ "recipient_device_id": device_second, "message_type": 2, "ciphertext": "AA==" }],
            }),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn direct_chat_send_with_zero_envelopes_is_still_rejected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();

    let (token_a, id_a, _dev_a) = register_user(&app, ip, "svy_a_").await;
    let (_token_b, id_b, _dev_b) = register_user(&app, ip, "svy_b_").await;

    let direct = app
        .clone()
        .oneshot(post_json_request("/chats/direct", ip, &token_a, json!({ "other_user_id": id_b })))
        .await
        .unwrap();
    let direct_id = body_json(direct).await["chat_id"].as_str().unwrap().to_string();

    let response = app
        .oneshot(post_json_request(
            &format!("/chats/{direct_id}/messages"),
            ip,
            &token_a,
            json!({
                "client_message_id": Uuid::new_v4(),
                "kind": "content",
                "envelopes": [],
            }),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let _ = id_a;
}

#[tokio::test]
async fn cannot_call_yourself_even_via_saved_chat() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let (token_a, id_a, _dev_a) = register_user(&app, ip, "svc_").await;

    let response = app
        .oneshot(post_json_request("/calls", ip, &token_a, json!({ "callee_user_id": id_a, "media_kind": "audio" })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn saved_chat_has_no_message_receipts() {
    let state = spawn_state().await;
    let app = messenger_server::app(state);
    let ip = connect_info();
    let username = unique_username("svr_");
    let device_a = Uuid::new_v4();
    let reg = app
        .clone()
        .oneshot(register_request(ip, &username, device_a))
        .await
        .unwrap();
    let token_owner = body_json(reg).await["access_token"].as_str().unwrap().to_string();

    let (_token_second, device_second) = login_second_device(&app, ip, &username).await;

    let saved = app
        .clone()
        .oneshot(get_request("/chats/saved", ip, Some(&token_owner)))
        .await
        .unwrap();
    let saved_id = body_json(saved).await["chat_id"].as_str().unwrap().to_string();

    let send = app
        .clone()
        .oneshot(post_json_request(
            &format!("/chats/{saved_id}/messages"),
            ip,
            &token_owner,
            json!({
                "client_message_id": Uuid::new_v4(),
                "kind": "content",
                "envelopes": [{ "recipient_device_id": device_second, "message_type": 2, "ciphertext": "AA==" }],
            }),
        ))
        .await
        .unwrap();
    assert_eq!(send.status(), StatusCode::OK);

    let pool = sqlx::postgres::PgPoolOptions::new()
        .max_connections(2)
        .connect(&std::env::var("TEST_DATABASE_URL")
            .unwrap_or_else(|_| "postgres://messenger:messenger@localhost:5432/messenger_test".to_string()))
        .await
        .unwrap();
    let (count,): (i64,) = sqlx::query_as(
        "SELECT COUNT(*) FROM message_receipts mr JOIN messages m ON m.id = mr.message_id WHERE m.chat_id = $1",
    )
    .bind(Uuid::parse_str(&saved_id).unwrap())
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(count, 0, "saved chats must not fan out receipts");
}
