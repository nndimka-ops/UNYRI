//! Scale test for `GET /sync` pagination over a large (10k-message) dataset.
//! Messages are seeded via direct bulk SQL (not the HTTP send endpoint) -
//! this test is specifically about `/sync`'s pagination/index behavior at
//! scale, not send-path throughput (already covered by other test files).

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

const MESSAGE_COUNT: usize = 10_000;

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

async fn create_direct_chat(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, other_user_id: &str) -> String {
    let response = app
        .clone()
        .oneshot(post_json_request("/chats/direct", ip, token, json!({ "other_user_id": other_user_id })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await["chat_id"].as_str().unwrap().to_string()
}

/// `#[ignore]`d by default - this seeds 10k rows and is meaningfully slower
/// than the rest of the suite; run explicitly via
/// `cargo test --test scale_test -- --ignored --nocapture`.
#[tokio::test]
#[ignore]
async fn sync_paginates_10k_messages_with_no_duplicates_and_bounded_pages() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token_a, id_a, _device_a) = register_user(&app, ip, "scale_a_").await;
    let (token_b, id_b, device_b) = register_user(&app, ip, "scale_b_").await;
    let chat_id = create_direct_chat(&app, ip, &token_a, &id_b).await;
    let chat_uuid = Uuid::parse_str(&chat_id).unwrap();
    let sender_id = Uuid::parse_str(&id_a).unwrap();
    let recipient_device_id = Uuid::parse_str(&device_b).unwrap();

    // sender_device_id: fetch A's own device row.
    let (sender_device_id,): (Uuid,) = sqlx::query_as("SELECT id FROM devices WHERE user_id = $1")
        .bind(sender_id)
        .fetch_one(&state.db)
        .await
        .unwrap();

    let seed_started = std::time::Instant::now();
    let ciphertext = STANDARD.decode(STANDARD.encode(b"scale-test-ciphertext")).unwrap();
    for batch_start in (0..MESSAGE_COUNT).step_by(500) {
        let batch_end = (batch_start + 500).min(MESSAGE_COUNT);
        let mut tx = state.db.begin().await.unwrap();
        for _ in batch_start..batch_end {
            let message_id: Uuid = sqlx::query_scalar(
                "INSERT INTO messages (chat_id, sender_id, sender_device_id, client_message_id, ciphertext, encryption_version) \
                 VALUES ($1, $2, $3, $4, NULL, 1) RETURNING id",
            )
            .bind(chat_uuid)
            .bind(sender_id)
            .bind(sender_device_id)
            .bind(Uuid::new_v4())
            .fetch_one(&mut *tx)
            .await
            .unwrap();

            sqlx::query(
                "INSERT INTO message_envelopes (message_id, recipient_device_id, message_type, ciphertext) \
                 VALUES ($1, $2, 2, $3)",
            )
            .bind(message_id)
            .bind(recipient_device_id)
            .bind(&ciphertext)
            .execute(&mut *tx)
            .await
            .unwrap();
        }
        tx.commit().await.unwrap();
    }
    eprintln!("seeded {MESSAGE_COUNT} messages in {:?}", seed_started.elapsed());

    // Paginate /sync from scratch, bounded page size, verifying no
    // duplicates/gaps-in-coverage and reasonable wall-clock time (bounded
    // memory: each page request only ever holds `limit` rows at once).
    let paginate_started = std::time::Instant::now();
    let mut seen = std::collections::HashSet::with_capacity(MESSAGE_COUNT);
    let mut after: i64 = 0;
    let mut pages = 0usize;
    loop {
        let uri = format!("/sync?after_sequence={after}&limit=500");
        let response = app.clone().oneshot(get_request(&uri, ip, &token_b)).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = body_json(response).await;
        let messages = body["messages"].as_array().unwrap().clone();
        assert!(messages.len() <= 500, "a page must never exceed the requested/clamped limit");
        for m in &messages {
            assert!(seen.insert(m["id"].as_str().unwrap().to_string()), "duplicate message across /sync pages");
        }
        pages += 1;
        after = body["next_sequence"].as_i64().unwrap();
        if !body["has_more"].as_bool().unwrap() {
            break;
        }
    }
    let elapsed = paginate_started.elapsed();
    eprintln!("paginated {} messages across {pages} pages in {:?}", seen.len(), elapsed);

    assert_eq!(seen.len(), MESSAGE_COUNT, "every seeded message must be retrievable exactly once via /sync");
    assert_eq!(pages, MESSAGE_COUNT / 500, "page count must match limit=500 exactly with no partial/duplicate pages");
    assert!(elapsed.as_secs() < 30, "pagination over 10k messages took unreasonably long: {elapsed:?}");

    // EXPLAIN ANALYZE on the exact query /sync runs, for the report.
    let plan: Vec<(String,)> = sqlx::query_as(
        "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) \
         SELECT m.id, m.chat_id, m.sender_id, m.sender_device_id, m.client_message_id, m.sequence, m.created_at, \
                me.message_type, me.ciphertext \
         FROM messages m \
         JOIN message_envelopes me ON me.message_id = m.id AND me.recipient_device_id = $1 \
         WHERE m.sequence > 0 AND m.deleted_at IS NULL \
         ORDER BY m.sequence ASC \
         LIMIT 501",
    )
    .bind(recipient_device_id)
    .fetch_all(&state.db)
    .await
    .unwrap();
    eprintln!("--- EXPLAIN ANALYZE for the /sync query ---");
    for (line,) in &plan {
        eprintln!("{line}");
    }
}
