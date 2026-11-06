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
use messenger_server::devices::handlers::{EVENT_CONTACT_DEVICE_ADDED, EVENT_CONTACT_IDENTITY_CHANGED, EVENT_NEW_DEVICE};
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

fn get_request(uri: &str, ip: ConnectInfo<SocketAddr>, token: &str) -> Request<Body> {
    Request::builder()
        .method("GET")
        .uri(uri)
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(Body::empty())
        .unwrap()
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

/// Registers a fresh user/device, returns (access_token, user_id, device_id).
async fn register_user(app: &axum::Router, ip: ConnectInfo<SocketAddr>, prefix: &str) -> (String, String, String) {
    let username = unique_username(prefix);
    let response = app.clone().oneshot(register_request(ip, &username)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        body["device_id"].as_str().unwrap().to_string(),
    )
}

async fn direct_chat_id(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, other_user_id: &str) -> String {
    let response = app
        .clone()
        .oneshot(post_json_request("/chats/direct", ip, token, json!({ "other_user_id": other_user_id })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await["chat_id"].as_str().unwrap().to_string()
}

fn upload_keys_body_with_identity(identity: &[u8], otp_ids: &[i32]) -> Value {
    json!({
        "identity_key": b64(identity),
        "registration_id": 12345,
        "signed_prekey": { "key_id": 1, "public_key": b64(b"fake-signed-prekey"), "signature": b64(b"fake-signature") },
        "kyber_prekey": { "key_id": 1, "public_key": b64(b"fake-kyber-prekey"), "signature": b64(b"fake-kyber-signature") },
        "one_time_prekeys": otp_ids.iter().map(|id| json!({
            "key_id": id, "public_key": b64(format!("fake-otp-{id}").as_bytes()),
        })).collect::<Vec<_>>(),
    })
}

async fn upload_keys(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, identity: &[u8], otp_ids: &[i32]) {
    let response = app
        .clone()
        .oneshot(post_json_request("/keys", ip, token, upload_keys_body_with_identity(identity, otp_ids)))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

/// Conversation events ABOUT `subject_user_id` (the user whose keys changed).
/// Scoped to the subject so this test file is isolated from other test files
/// that also write into the shared `security_events` table.
/// (event_type, user_id, chat_id, subject_user_id, subject_device_id, event_key)
#[allow(clippy::type_complexity)]
async fn conversation_events_for_subject(state: &AppState, subject_user_id: &str) -> Vec<(String, Uuid, Uuid, Uuid, Uuid, String)> {
    sqlx::query_as(
        "SELECT event_type, user_id, chat_id, subject_user_id, subject_device_id, event_key \
         FROM security_events WHERE scope = 'conversation' AND subject_user_id = $1 ORDER BY id",
    )
    .bind(Uuid::parse_str(subject_user_id).unwrap())
    .fetch_all(&state.db)
    .await
    .unwrap()
}

async fn account_new_device_count(state: &AppState, user_ids: &[&str]) -> i64 {
    let uuids: Vec<Uuid> = user_ids.iter().map(|s| Uuid::parse_str(s).unwrap()).collect();
    sqlx::query_scalar(
        "SELECT count(*) FROM security_events \
         WHERE scope = 'account' AND event_type = $1 AND user_id = ANY($2)",
    )
    .bind(EVENT_NEW_DEVICE)
    .bind(&uuids)
    .fetch_one(&state.db)
    .await
    .unwrap()
}

// ---------------------------------------------------------------------------

#[tokio::test]
async fn login_and_chat_creation_alone_emit_no_contact_events() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, _alice_device) = register_user(&app, ip, "ce_a_").await;
    let (_bob_token, bob_id, _bob_device) = register_user(&app, ip, "ce_b_").await;
    let _chat = direct_chat_id(&app, ip, &alice_token, &bob_id).await;

    // Login/device-row creation + chat creation alone emit zero conversation rows
    // for either fresh user.
    assert!(conversation_events_for_subject(&state, &alice_id).await.is_empty());
    assert!(conversation_events_for_subject(&state, &bob_id).await.is_empty());

    // Owner-facing new_device events still exist (account scope) for both users.
    assert_eq!(account_new_device_count(&state, &[&alice_id, &bob_id]).await, 2);
}

#[tokio::test]
async fn first_identity_upload_emits_contact_device_added() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, alice_device) = register_user(&app, ip, "ce_a2_").await;
    let (_bob_token, bob_id, _bob_device) = register_user(&app, ip, "ce_b2_").await;
    let chat_id = direct_chat_id(&app, ip, &alice_token, &bob_id).await;

    upload_keys(&app, ip, &alice_token, b"identity-v1", &[1, 2]).await;

    let events = conversation_events_for_subject(&state, &alice_id).await;
    assert_eq!(events.len(), 1);
    let (event_type, recipient, chat, subject_user, subject_dev, event_key) = &events[0];
    assert_eq!(event_type, EVENT_CONTACT_DEVICE_ADDED);
    assert_eq!(*recipient, Uuid::parse_str(&bob_id).unwrap());
    assert_eq!(*chat, Uuid::parse_str(&chat_id).unwrap());
    assert_eq!(*subject_user, Uuid::parse_str(&alice_id).unwrap());
    assert_eq!(*subject_dev, Uuid::parse_str(&alice_device).unwrap());
    assert_eq!(event_key, &format!("{EVENT_CONTACT_DEVICE_ADDED}:{alice_id}:{alice_device}"));
    // No identity-change event for a first insert.
    assert!(events.iter().all(|(t, ..)| t != EVENT_CONTACT_IDENTITY_CHANGED));
}

#[tokio::test]
async fn same_identity_reupload_emits_nothing_new() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, _alice_device) = register_user(&app, ip, "ce_a3_").await;
    let (_bob_token, bob_id, _bob_device) = register_user(&app, ip, "ce_b3_").await;
    let _chat = direct_chat_id(&app, ip, &alice_token, &bob_id).await;

    upload_keys(&app, ip, &alice_token, b"identity-v1", &[1]).await;
    upload_keys(&app, ip, &alice_token, b"identity-v1", &[2]).await;

    let events = conversation_events_for_subject(&state, &alice_id).await;
    assert_eq!(events.len(), 1, "same identity re-upload must not add an event");
}

#[tokio::test]
async fn identity_change_emits_once_and_distinct_later_change_adds_second() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, alice_device) = register_user(&app, ip, "ce_a4_").await;
    let (_bob_token, bob_id, _bob_device) = register_user(&app, ip, "ce_b4_").await;
    let chat_id = direct_chat_id(&app, ip, &alice_token, &bob_id).await;

    upload_keys(&app, ip, &alice_token, b"identity-v1", &[1]).await; // first -> device_added
    upload_keys(&app, ip, &alice_token, b"identity-v2", &[2]).await; // K1->K2 -> identity_changed
    upload_keys(&app, ip, &alice_token, b"identity-v2", &[3]).await; // re-upload K2 -> nothing

    let after_k2 = conversation_events_for_subject(&state, &alice_id).await;
    assert_eq!(after_k2.len(), 2, "device_added + one identity_changed");
    assert_eq!(after_k2.iter().filter(|(t, ..)| t == EVENT_CONTACT_IDENTITY_CHANGED).count(), 1);

    let changed = after_k2.iter().find(|(t, ..)| t == EVENT_CONTACT_IDENTITY_CHANGED).unwrap();
    assert_eq!(changed.1, Uuid::parse_str(&bob_id).unwrap());
    assert_eq!(changed.3, Uuid::parse_str(&alice_id).unwrap());
    assert_eq!(changed.4, Uuid::parse_str(&alice_device).unwrap());
    assert_eq!(changed.2, Uuid::parse_str(&chat_id).unwrap());

    // K2 -> K3 is a distinct transition -> second identity_changed row.
    upload_keys(&app, ip, &alice_token, b"identity-v3", &[4]).await;
    let after_k3 = conversation_events_for_subject(&state, &alice_id).await;
    assert_eq!(after_k3.iter().filter(|(t, ..)| t == EVENT_CONTACT_IDENTITY_CHANGED).count(), 2);
    let identity_keys: std::collections::HashSet<String> = after_k3
        .iter()
        .filter(|(t, ..)| t == EVENT_CONTACT_IDENTITY_CHANGED)
        .map(|(_, _, _, _, _, k)| k.clone())
        .collect();
    assert_eq!(identity_keys.len(), 2, "K2 and K3 transitions must have distinct event_keys");
}

#[tokio::test]
async fn subject_never_receives_own_event_and_saved_chat_excluded() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, alice_device) = register_user(&app, ip, "ce_a5_").await;
    let (_bob_token, bob_id, _bob_device) = register_user(&app, ip, "ce_b5_").await;
    let _direct = direct_chat_id(&app, ip, &alice_token, &bob_id).await;

    // Alice's saved/self chat must exist but must never receive a contact event.
    let saved_response = app.clone().oneshot(get_request("/chats/saved", ip, &alice_token)).await.unwrap();
    assert_eq!(saved_response.status(), StatusCode::OK);
    let saved_chat_id = body_json(saved_response).await["chat_id"].as_str().unwrap().to_string();

    upload_keys(&app, ip, &alice_token, b"identity-v1", &[1]).await;

    let events = conversation_events_for_subject(&state, &alice_id).await;
    assert_eq!(events.len(), 1);
    let (_, recipient, chat, subject_user, _, _) = &events[0];
    assert_eq!(*recipient, Uuid::parse_str(&bob_id).unwrap());
    assert_eq!(*subject_user, Uuid::parse_str(&alice_id).unwrap());
    assert_ne!(*chat, Uuid::parse_str(&saved_chat_id).unwrap(), "saved chat must not receive a contact event");
    assert_ne!(recipient, &Uuid::parse_str(&alice_id).unwrap(), "subject never receives their own event");
    let _ = alice_device;
}

#[tokio::test]
async fn no_direct_chat_produces_zero_rows() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, _alice_device) = register_user(&app, ip, "ce_a6_").await;

    upload_keys(&app, ip, &alice_token, b"identity-v1", &[1]).await;

    assert!(conversation_events_for_subject(&state, &alice_id).await.is_empty());
}

#[tokio::test]
async fn multiple_partners_get_one_row_each() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, alice_device) = register_user(&app, ip, "ce_a7_").await;
    let (_bob_token, bob_id, _bob_device) = register_user(&app, ip, "ce_b7_").await;
    let (_charlie_token, charlie_id, _charlie_device) = register_user(&app, ip, "ce_c7_").await;
    let bob_chat = direct_chat_id(&app, ip, &alice_token, &bob_id).await;
    let charlie_chat = direct_chat_id(&app, ip, &alice_token, &charlie_id).await;

    upload_keys(&app, ip, &alice_token, b"identity-v1", &[1]).await;

    let events = conversation_events_for_subject(&state, &alice_id).await;
    assert_eq!(events.len(), 2);
    let recipients: std::collections::HashSet<Uuid> = events.iter().map(|(_, r, ..)| *r).collect();
    assert!(recipients.contains(&Uuid::parse_str(&bob_id).unwrap()));
    assert!(recipients.contains(&Uuid::parse_str(&charlie_id).unwrap()));
    assert!(!recipients.contains(&Uuid::parse_str(&alice_id).unwrap()));

    let chats: std::collections::HashSet<Uuid> = events.iter().map(|(_, _, c, ..)| *c).collect();
    assert!(chats.contains(&Uuid::parse_str(&bob_chat).unwrap()));
    assert!(chats.contains(&Uuid::parse_str(&charlie_chat).unwrap()));

    // Same event_key for all partners; dedup key is (recipient, chat, event_key).
    let keys: std::collections::HashSet<&String> = events.iter().map(|(_, _, _, _, _, k)| k).collect();
    assert_eq!(keys.len(), 1);
    let _ = (alice_device, alice_id);
}

#[tokio::test]
async fn event_payload_never_contains_installation_id_or_metadata_secrets() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, alice_device) = register_user(&app, ip, "ce_a8_").await;
    let (_bob_token, bob_id, _bob_device) = register_user(&app, ip, "ce_b8_").await;
    let _chat = direct_chat_id(&app, ip, &alice_token, &bob_id).await;

    upload_keys(&app, ip, &alice_token, b"identity-v1", &[1]).await;

    let events = conversation_events_for_subject(&state, &alice_id).await;
    assert_eq!(events.len(), 1);
    let (event_type, _, _, _, _, event_key) = &events[0];
    // event_key contains only type + subject user + subject account-device.
    assert_eq!(event_key, &format!("{EVENT_CONTACT_DEVICE_ADDED}:{alice_id}:{alice_device}"));
    assert!(!event_key.contains("installation"));
    assert_eq!(event_type, EVENT_CONTACT_DEVICE_ADDED);

    // metadata is empty JSONB for conversation events (no hidden fields).
    let metadata: serde_json::Value = sqlx::query_scalar(
        "SELECT metadata FROM security_events WHERE scope = 'conversation' AND subject_user_id = $1 AND event_key = $2",
    )
    .bind(Uuid::parse_str(&alice_id).unwrap())
    .bind(event_key)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(metadata, serde_json::json!({}));
}
