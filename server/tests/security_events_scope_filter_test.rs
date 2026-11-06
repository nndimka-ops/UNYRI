use std::net::SocketAddr;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use serde_json::{json, Value};
use tower::ServiceExt;
use uuid::Uuid;

use messenger_server::config::{Config, S3Config};
use messenger_server::devices::handlers::{
    record_conversation_security_event, record_security_event, EVENT_CONTACT_DEVICE_ADDED,
    EVENT_CONTACT_IDENTITY_CHANGED, EVENT_DEVICE_REVOKED, EVENT_NEW_DEVICE,
};
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

fn authed_json(uri: &str, ip: ConnectInfo<SocketAddr>, token: &str, body: Value) -> Request<Body> {
    Request::builder()
        .method("POST")
        .uri(uri)
        .header("content-type", "application/json")
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(Body::from(body.to_string()))
        .unwrap()
}

fn get_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: &str) -> Request<Body> {
    Request::builder()
        .method("GET")
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

/// Registers a fresh user, returns (access_token, user_id, device_id).
async fn register_user(app: &axum::Router, ip: ConnectInfo<SocketAddr>, prefix: &str) -> (String, Uuid, Uuid) {
    let username = unique_username(prefix);
    let response = app.clone().oneshot(register_request(ip, &username)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        Uuid::parse_str(body["user"]["id"].as_str().unwrap()).unwrap(),
        Uuid::parse_str(body["device_id"].as_str().unwrap()).unwrap(),
    )
}

async fn direct_chat_id(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, other_user_id: Uuid) -> Uuid {
    let response = app
        .clone()
        .oneshot(authed_json("/chats/direct", ip, token, json!({ "other_user_id": other_user_id })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    Uuid::parse_str(body["chat_id"].as_str().unwrap()).unwrap()
}

// ---------------------------------------------------------------------------
// Regression: GET /security/events must mean OWNER/ACCOUNT events only. The
// conversation-scope rows written by Checkpoint C must never leak into the
// owner feed, and must stay durably present in the table.
// ---------------------------------------------------------------------------

#[tokio::test]
async fn owner_security_events_endpoint_returns_only_account_scope_events() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();

    let (alice_token, alice_id, alice_device) = register_user(&app, ip, "scp_a_").await;
    let (bob_token, bob_id, bob_device) = register_user(&app, ip, "scp_b_").await;

    // An existing direct chat so Bob is a legitimate recipient of Alice's
    // conversation-scope events.
    let chat_id = direct_chat_id(&app, ip, &alice_token, bob_id).await;

    // Owner account event for Bob: a device revoke.
    let mut tx = state.db.begin().await.unwrap();
    record_security_event(&mut tx, bob_id, EVENT_DEVICE_REVOKED, Some(bob_device), Some(bob_device), json!({}))
        .await
        .unwrap();
    // Conversation-scope events for Bob (recipient), subject = Alice.
    record_conversation_security_event(
        &mut tx, bob_id, chat_id, alice_id, alice_device, EVENT_CONTACT_DEVICE_ADDED, "scp:device_added",
    )
    .await
    .unwrap();
    record_conversation_security_event(
        &mut tx, bob_id, chat_id, alice_id, alice_device, EVENT_CONTACT_IDENTITY_CHANGED, "scp:identity_changed",
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();

    // GET /security/events as Bob.
    let response = app.clone().oneshot(get_request("/security/events", ip, &bob_token)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    let event_types: Vec<String> = body["events"]
        .as_array()
        .unwrap()
        .iter()
        .map(|e| e["event_type"].as_str().unwrap().to_string())
        .collect();

    // 1. Owner new_device (from registration) is returned.
    assert!(event_types.contains(&EVENT_NEW_DEVICE.to_string()));
    // 2. Owner device_revoked is returned.
    assert!(event_types.contains(&EVENT_DEVICE_REVOKED.to_string()));
    // 3. Conversation contact_device_added is NOT returned.
    assert!(!event_types.contains(&EVENT_CONTACT_DEVICE_ADDED.to_string()));
    // 4. Conversation contact_identity_changed is NOT returned.
    assert!(!event_types.contains(&EVENT_CONTACT_IDENTITY_CHANGED.to_string()));

    // 5. Conversation rows remain present in the DB (not deleted by the fix).
    let conversation_count: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM security_events WHERE user_id = $1 AND scope = 'conversation'",
    )
    .bind(bob_id)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(conversation_count, 2);
}
