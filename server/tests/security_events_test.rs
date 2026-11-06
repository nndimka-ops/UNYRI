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
    record_conversation_security_event, record_security_event, SecurityEventInsertOutcome,
    EVENT_CONTACT_DEVICE_ADDED, EVENT_CONTACT_IDENTITY_CHANGED, EVENT_DEVICE_REVOKED, EVENT_NEW_DEVICE,
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

/// Creates (or reopens) a direct chat between `token`'s user and `other_user_id`.
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

/// (event_type, scope, chat_id, subject_user_id, subject_device_id, event_key) for one event.
#[allow(clippy::type_complexity)]
async fn stored_event(state: &AppState, user_id: Uuid, event_key: &str) -> (String, String, Option<Uuid>, Option<Uuid>, Option<Uuid>, String) {
    sqlx::query_as(
        "SELECT event_type, scope, chat_id, subject_user_id, subject_device_id, event_key \
         FROM security_events WHERE user_id = $1 AND event_key = $2",
    )
    .bind(user_id)
    .bind(event_key)
    .fetch_one(&state.db)
    .await
    .unwrap()
}

// ---------------------------------------------------------------------------
// Existing owner-facing account events stay intact (scope defaults to account).
// ---------------------------------------------------------------------------

#[tokio::test]
async fn owner_new_device_event_still_inserts_and_reads() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (_token, user_id, device_id) = register_user(&app, ip, "secown_").await;

    // The registration already emitted the owner-facing new_device event.
    let (event_type, scope, chat_id, subject_user_id, subject_device_id, event_key): (
        String, String, Option<Uuid>, Option<Uuid>, Option<Uuid>, Option<String>,
    ) = sqlx::query_as(
        "SELECT event_type, scope, chat_id, subject_user_id, subject_device_id, event_key \
         FROM security_events WHERE user_id = $1 AND event_type = $2",
    )
    .bind(user_id)
    .bind(EVENT_NEW_DEVICE)
    .fetch_one(&state.db)
    .await
    .unwrap();

    assert_eq!(event_type, EVENT_NEW_DEVICE);
    assert_eq!(scope, "account");
    assert_eq!(chat_id, None);
    assert_eq!(subject_user_id, None);
    assert_eq!(subject_device_id, Some(device_id));
    assert_eq!(event_key, None);

    // Existing GET /security/events still returns it.
    let response = app
        .clone()
        .oneshot(Request::builder().method("GET").uri("/security/events")
            .header("authorization", format!("Bearer {_token}")).extension(ip).body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["events"][0]["event_type"], EVENT_NEW_DEVICE);
    assert_eq!(body["events"][0]["subject_device_id"], device_id.to_string());
}

#[tokio::test]
async fn device_revoked_event_unaffected() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, user_id, _device_a) = register_user(&app, ip, "secrev_").await;

    // Log a legacy account event through the existing path (revoke of a device).
    let mut tx = state.db.begin().await.unwrap();
    record_security_event(&mut tx, user_id, EVENT_DEVICE_REVOKED, None, None, json!({})).await.unwrap();
    tx.commit().await.unwrap();

    let (event_type, scope, chat_id, subject_user_id, subject_device_id, event_key): (
        String, String, Option<Uuid>, Option<Uuid>, Option<Uuid>, Option<String>,
    ) = sqlx::query_as(
        "SELECT event_type, scope, chat_id, subject_user_id, subject_device_id, event_key \
         FROM security_events WHERE user_id = $1 AND event_type = $2",
    )
    .bind(user_id)
    .bind(EVENT_DEVICE_REVOKED)
    .fetch_one(&state.db)
    .await
    .unwrap();

    assert_eq!(event_type, EVENT_DEVICE_REVOKED);
    assert_eq!(scope, "account");
    assert_eq!(chat_id, None);
    assert_eq!(subject_user_id, None);
    assert_eq!(subject_device_id, None);
    assert_eq!(event_key, None);

    // GET /security/events still lists both account events.
    let response = app
        .clone()
        .oneshot(Request::builder().method("GET").uri("/security/events")
            .header("authorization", format!("Bearer {token_a}")).extension(ip).body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

// ---------------------------------------------------------------------------
// Conversation events: correct fields + recipient/subject semantics.
// ---------------------------------------------------------------------------

#[tokio::test]
async fn conversation_contact_device_added_stores_correctly() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, alice_device) = register_user(&app, ip, "secsubj_").await;
    let (_bob_token, bob_id, _bob_device) = register_user(&app, ip, "secrecip_").await;
    let chat_id = direct_chat_id(&app, ip, &alice_token, bob_id).await;

    let key = format!("{EVENT_CONTACT_DEVICE_ADDED}:{alice_device}");
    let mut tx = state.db.begin().await.unwrap();
    let outcome = record_conversation_security_event(
        &mut tx, bob_id, chat_id, alice_id, alice_device, EVENT_CONTACT_DEVICE_ADDED, &key,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();

    assert!(matches!(outcome, SecurityEventInsertOutcome::Inserted(_)));

    let (event_type, scope, chat, subject_user, subject_dev, event_key) = stored_event(&state, bob_id, &key).await;
    assert_eq!(event_type, EVENT_CONTACT_DEVICE_ADDED);
    assert_eq!(scope, "conversation");
    assert_eq!(chat, Some(chat_id));
    assert_eq!(subject_user, Some(alice_id));
    assert_eq!(subject_dev, Some(alice_device));
    assert_eq!(event_key, key);
}

#[tokio::test]
async fn conversation_contact_identity_changed_stores_correctly() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, alice_device) = register_user(&app, ip, "secidc_").await;
    let (_bob_token, bob_id, _bob_device) = register_user(&app, ip, "secrecip2_").await;
    let chat_id = direct_chat_id(&app, ip, &alice_token, bob_id).await;

    // event_key is a server-derived digest of the new public identity (Checkpoint C).
    let digest = "1111111111111111111111111111111111111111111111111111111111111111";
    let key = format!("{EVENT_CONTACT_IDENTITY_CHANGED}:{alice_device}:{digest}");
    let mut tx = state.db.begin().await.unwrap();
    let outcome = record_conversation_security_event(
        &mut tx, bob_id, chat_id, alice_id, alice_device, EVENT_CONTACT_IDENTITY_CHANGED, &key,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();

    assert!(matches!(outcome, SecurityEventInsertOutcome::Inserted(_)));
    let (event_type, scope, chat, subject_user, subject_dev, event_key) = stored_event(&state, bob_id, &key).await;
    assert_eq!(event_type, EVENT_CONTACT_IDENTITY_CHANGED);
    assert_eq!(scope, "conversation");
    assert_eq!(chat, Some(chat_id));
    assert_eq!(subject_user, Some(alice_id));
    assert_eq!(subject_dev, Some(alice_device));
    assert_eq!(event_key, key);
}

// ---------------------------------------------------------------------------
// Idempotency.
// ---------------------------------------------------------------------------

#[tokio::test]
async fn duplicate_device_added_transition_inserts_once() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, alice_device) = register_user(&app, ip, "secdedup_").await;
    let (_bob_token, bob_id, _bob_device) = register_user(&app, ip, "secrecip3_").await;
    let chat_id = direct_chat_id(&app, ip, &alice_token, bob_id).await;
    let key = format!("{EVENT_CONTACT_DEVICE_ADDED}:{alice_device}");

    let mut tx = state.db.begin().await.unwrap();
    let first = record_conversation_security_event(&mut tx, bob_id, chat_id, alice_id, alice_device, EVENT_CONTACT_DEVICE_ADDED, &key).await.unwrap();
    let second = record_conversation_security_event(&mut tx, bob_id, chat_id, alice_id, alice_device, EVENT_CONTACT_DEVICE_ADDED, &key).await.unwrap();
    tx.commit().await.unwrap();

    assert!(matches!(first, SecurityEventInsertOutcome::Inserted(_)));
    assert_eq!(second, SecurityEventInsertOutcome::AlreadyExists);

    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM security_events WHERE user_id = $1 AND event_key = $2")
        .bind(bob_id).bind(&key).fetch_one(&state.db).await.unwrap();
    assert_eq!(count, 1);
}

#[tokio::test]
async fn duplicate_identity_change_transition_inserts_once_and_distinct_allows_second() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, alice_device) = register_user(&app, ip, "secidem_").await;
    let (_bob_token, bob_id, _bob_device) = register_user(&app, ip, "secrecip4_").await;
    let chat_id = direct_chat_id(&app, ip, &alice_token, bob_id).await;

    // K1 -> K2 transition (digest of the NEW key K2).
    let k2 = "2222222222222222222222222222222222222222222222222222222222222222";
    let key_k2 = format!("{EVENT_CONTACT_IDENTITY_CHANGED}:{alice_device}:{k2}");

    let mut tx = state.db.begin().await.unwrap();
    let first = record_conversation_security_event(&mut tx, bob_id, chat_id, alice_id, alice_device, EVENT_CONTACT_IDENTITY_CHANGED, &key_k2).await.unwrap();
    let retry = record_conversation_security_event(&mut tx, bob_id, chat_id, alice_id, alice_device, EVENT_CONTACT_IDENTITY_CHANGED, &key_k2).await.unwrap();
    assert!(matches!(first, SecurityEventInsertOutcome::Inserted(_)));
    assert_eq!(retry, SecurityEventInsertOutcome::AlreadyExists);

    // Later K2 -> K3 transition is a DIFFERENT event -> second row allowed.
    let k3 = "3333333333333333333333333333333333333333333333333333333333333333";
    let key_k3 = format!("{EVENT_CONTACT_IDENTITY_CHANGED}:{alice_device}:{k3}");
    let later = record_conversation_security_event(&mut tx, bob_id, chat_id, alice_id, alice_device, EVENT_CONTACT_IDENTITY_CHANGED, &key_k3).await.unwrap();
    tx.commit().await.unwrap();

    assert!(matches!(later, SecurityEventInsertOutcome::Inserted(_)));
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM security_events WHERE user_id = $1 AND event_type = $2")
        .bind(bob_id).bind(EVENT_CONTACT_IDENTITY_CHANGED).fetch_one(&state.db).await.unwrap();
    assert_eq!(count, 2);
}

#[tokio::test]
async fn same_transition_creates_separate_rows_for_each_recipient() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, alice_device) = register_user(&app, ip, "secmulti_").await;
    let (_bob_token, bob_id, _bob_device) = register_user(&app, ip, "secrecip5_").await;
    let (_charlie_token, charlie_id, _charlie_device) = register_user(&app, ip, "secrecip6_").await;
    let bob_chat = direct_chat_id(&app, ip, &alice_token, bob_id).await;
    let charlie_chat = direct_chat_id(&app, ip, &alice_token, charlie_id).await;
    let key = format!("{EVENT_CONTACT_DEVICE_ADDED}:{alice_device}");

    let mut tx = state.db.begin().await.unwrap();
    let for_bob = record_conversation_security_event(&mut tx, bob_id, bob_chat, alice_id, alice_device, EVENT_CONTACT_DEVICE_ADDED, &key).await.unwrap();
    let for_charlie = record_conversation_security_event(&mut tx, charlie_id, charlie_chat, alice_id, alice_device, EVENT_CONTACT_DEVICE_ADDED, &key).await.unwrap();
    tx.commit().await.unwrap();

    assert!(matches!(for_bob, SecurityEventInsertOutcome::Inserted(_)));
    assert!(matches!(for_charlie, SecurityEventInsertOutcome::Inserted(_)));

    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM security_events WHERE event_key = $1")
        .bind(&key).fetch_one(&state.db).await.unwrap();
    assert_eq!(count, 2);
}

// ---------------------------------------------------------------------------
// Validation + privacy.
// ---------------------------------------------------------------------------

#[tokio::test]
async fn conversation_event_requires_event_key_and_valid_fks() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, alice_device) = register_user(&app, ip, "secval_").await;
    let (_bob_token, bob_id, _bob_device) = register_user(&app, ip, "secrecip7_").await;
    let chat_id = direct_chat_id(&app, ip, &alice_token, bob_id).await;

    // Empty event_key is rejected.
    let mut tx = state.db.begin().await.unwrap();
    let empty = record_conversation_security_event(&mut tx, bob_id, chat_id, alice_id, alice_device, EVENT_CONTACT_DEVICE_ADDED, "").await;
    assert!(empty.is_err());
    tx.rollback().await.unwrap();

    // A chat_id that does not exist violates the FK -> rejected.
    let mut tx = state.db.begin().await.unwrap();
    let bad_chat = record_conversation_security_event(&mut tx, bob_id, Uuid::new_v4(), alice_id, alice_device, EVENT_CONTACT_DEVICE_ADDED, "k").await;
    assert!(bad_chat.is_err());
    tx.rollback().await.unwrap();
}

#[tokio::test]
async fn conversation_event_never_exposes_installation_id_or_private_material() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, alice_device) = register_user(&app, ip, "secpriv_").await;
    let (_bob_token, bob_id, _bob_device) = register_user(&app, ip, "secrecip8_").await;
    let chat_id = direct_chat_id(&app, ip, &alice_token, bob_id).await;

    // A stand-in for a private Signal identity key; it must never be stored.
    let private_key_hex = "0d0e0f10_private_identity_key_bytes_never_stored";
    // The idempotency key is a digest of the PUBLIC identity only.
    let digest = "4444444444444444444444444444444444444444444444444444444444444444";
    let key = format!("{EVENT_CONTACT_IDENTITY_CHANGED}:{alice_device}:{digest}");

    let mut tx = state.db.begin().await.unwrap();
    record_conversation_security_event(&mut tx, bob_id, chat_id, alice_id, alice_device, EVENT_CONTACT_IDENTITY_CHANGED, &key).await.unwrap();
    tx.commit().await.unwrap();

    let (event_key, metadata): (String, serde_json::Value) = sqlx::query_as(
        "SELECT event_key, metadata FROM security_events WHERE user_id = $1 AND event_key = $2",
    )
    .bind(bob_id).bind(&key).fetch_one(&state.db).await.unwrap();

    assert_eq!(event_key, key);
    assert!(!event_key.contains(private_key_hex));
    assert_eq!(metadata, serde_json::json!({}));
}

// ---------------------------------------------------------------------------
// B.1: DB-level invariants exercised directly against PostgreSQL constraints.
// ---------------------------------------------------------------------------

#[tokio::test]
async fn db_rejects_conversation_rows_with_missing_fields() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (alice_token, alice_id, alice_device) = register_user(&app, ip, "secinv_").await;
    let (_bob_token, bob_id, _bob_device) = register_user(&app, ip, "secrecip9_").await;
    let chat_id = direct_chat_id(&app, ip, &alice_token, bob_id).await;

    // NULL chat_id.
    let r = sqlx::query(
        "INSERT INTO security_events (user_id, event_type, subject_device_id, subject_user_id, chat_id, scope, event_key, metadata) \
         VALUES ($1, $2, $3, $4, NULL, 'conversation', $5, '{}'::jsonb)",
    )
    .bind(bob_id).bind(EVENT_CONTACT_DEVICE_ADDED).bind(alice_device).bind(alice_id).bind("k-chat-null")
    .execute(&state.db).await;
    assert!(r.is_err(), "NULL chat_id must be rejected by the DB");

    // NULL subject_user_id.
    let r = sqlx::query(
        "INSERT INTO security_events (user_id, event_type, subject_device_id, subject_user_id, chat_id, scope, event_key, metadata) \
         VALUES ($1, $2, $3, NULL, $4, 'conversation', $5, '{}'::jsonb)",
    )
    .bind(bob_id).bind(EVENT_CONTACT_DEVICE_ADDED).bind(alice_device).bind(chat_id).bind("k-subj-null")
    .execute(&state.db).await;
    assert!(r.is_err(), "NULL subject_user_id must be rejected by the DB");

    // NULL event_key.
    let r = sqlx::query(
        "INSERT INTO security_events (user_id, event_type, subject_device_id, subject_user_id, chat_id, scope, event_key, metadata) \
         VALUES ($1, $2, $3, $4, $5, 'conversation', NULL, '{}'::jsonb)",
    )
    .bind(bob_id).bind(EVENT_CONTACT_DEVICE_ADDED).bind(alice_device).bind(alice_id).bind(chat_id)
    .execute(&state.db).await;
    assert!(r.is_err(), "NULL event_key must be rejected by the DB");

    // Blank event_key.
    let r = sqlx::query(
        "INSERT INTO security_events (user_id, event_type, subject_device_id, subject_user_id, chat_id, scope, event_key, metadata) \
         VALUES ($1, $2, $3, $4, $5, 'conversation', '', '{}'::jsonb)",
    )
    .bind(bob_id).bind(EVENT_CONTACT_DEVICE_ADDED).bind(alice_device).bind(alice_id).bind(chat_id)
    .execute(&state.db).await;
    assert!(r.is_err(), "blank event_key must be rejected by the DB");

    // Whitespace-only event_key.
    let r = sqlx::query(
        "INSERT INTO security_events (user_id, event_type, subject_device_id, subject_user_id, chat_id, scope, event_key, metadata) \
         VALUES ($1, $2, $3, $4, $5, 'conversation', '   ', '{}'::jsonb)",
    )
    .bind(bob_id).bind(EVENT_CONTACT_DEVICE_ADDED).bind(alice_device).bind(alice_id).bind(chat_id)
    .execute(&state.db).await;
    assert!(r.is_err(), "whitespace-only event_key must be rejected by the DB");
}

#[tokio::test]
async fn account_legacy_row_with_null_chat_fields_remains_valid() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (_token, user_id, _device_id) = register_user(&app, ip, "secacct_").await;

    // An account-scope row with NULL chat_id / subject_user_id / event_key is
    // legal and unaffected by the conversation completeness CHECK.
    let r = sqlx::query(
        "INSERT INTO security_events (user_id, event_type, subject_device_id, subject_user_id, chat_id, scope, event_key, metadata) \
         VALUES ($1, $2, NULL, NULL, NULL, 'account', NULL, '{}'::jsonb)",
    )
    .bind(user_id).bind(EVENT_NEW_DEVICE)
    .execute(&state.db).await;
    assert!(r.is_ok(), "account row with NULL chat fields must remain valid");

    let (scope, chat_id, subject_user_id, event_key): (String, Option<Uuid>, Option<Uuid>, Option<String>) =
        sqlx::query_as(
            "SELECT scope, chat_id, subject_user_id, event_key FROM security_events WHERE user_id = $1 AND event_type = $2 AND chat_id IS NULL ORDER BY id DESC LIMIT 1",
        )
        .bind(user_id).bind(EVENT_NEW_DEVICE)
        .fetch_one(&state.db).await.unwrap();
    assert_eq!(scope, "account");
    assert_eq!(chat_id, None);
    assert_eq!(subject_user_id, None);
    assert_eq!(event_key, None);
}

#[tokio::test]
async fn same_event_key_in_different_chats_is_not_suppressed() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (_alice_token, alice_id, alice_device) = register_user(&app, ip, "secchat1_").await;
    let (bob_token, bob_id, _bob_device) = register_user(&app, ip, "secchat2_").await;
    let (_charlie_token, charlie_id, charlie_device) = register_user(&app, ip, "secchat3_").await;
    let bob_alice_chat = direct_chat_id(&app, ip, &bob_token, alice_id).await;
    let bob_charlie_chat = direct_chat_id(&app, ip, &bob_token, charlie_id).await;

    // Same event_key, two DIFFERENT chats for Bob -> two rows, never one
    // suppressing the other (uniqueness is per (recipient, chat, event_key)).
    let key = format!("{EVENT_CONTACT_DEVICE_ADDED}:shared-key");
    let mut tx = state.db.begin().await.unwrap();
    let a = record_conversation_security_event(&mut tx, bob_id, bob_alice_chat, alice_id, alice_device, EVENT_CONTACT_DEVICE_ADDED, &key).await.unwrap();
    let b = record_conversation_security_event(&mut tx, bob_id, bob_charlie_chat, charlie_id, charlie_device, EVENT_CONTACT_DEVICE_ADDED, &key).await.unwrap();
    tx.commit().await.unwrap();

    assert!(matches!(a, SecurityEventInsertOutcome::Inserted(_)));
    assert!(matches!(b, SecurityEventInsertOutcome::Inserted(_)));

    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM security_events WHERE user_id = $1 AND event_key = $2")
        .bind(bob_id).bind(&key).fetch_one(&state.db).await.unwrap();
    assert_eq!(count, 2, "same event_key in different chats must not be suppressed");
}
