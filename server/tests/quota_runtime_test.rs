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
    let endpoint = std::env::var("S3_ENDPOINT_URL").unwrap_or_else(|_| "http://127.0.0.1:59000".to_string());
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
        .unwrap_or_else(|_| "postgres://messenger:qa-pg-pass@127.0.0.1:55432/messenger_test".to_string());
    let redis_url = std::env::var("TEST_REDIS_URL")
        .unwrap_or_else(|_| "redis://:qa-redis-pass@127.0.0.1:56379/1".to_string());
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

fn connect_info() -> ConnectInfo<SocketAddr> {
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    ConnectInfo(SocketAddr::from(([10, octets[0], octets[1], octets[2]], 12345)))
}

async fn body_json(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
}

async fn register(app: &axum::Router, ip: ConnectInfo<SocketAddr>) -> (String, String, String) {
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/auth/register")
                .header("content-type", "application/json")
                .extension(ip)
                .body(Body::from(
                    json!({
                        "username": format!("quota{}", Uuid::new_v4().simple()).chars().take(32).collect::<String>(),
                        "password": "correct-horse-battery",
                        "display_name": "Quota User",
                        "installation_id": Uuid::new_v4(),
                        "device_platform": "android",
                        "device_name": "Quota Test",
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    (
        body["access_token"].as_str().unwrap().to_string(),
        body["user"]["id"].as_str().unwrap().to_string(),
        body["device_id"].as_str().unwrap().to_string(),
    )
}

fn init_request(ip: ConnectInfo<SocketAddr>, token: &str, size: i64) -> Request<Body> {
    Request::builder()
        .method("POST")
        .uri("/attachments")
        .header("content-type", "application/json")
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(Body::from(
            json!({ "client_attachment_id": Uuid::new_v4(), "encrypted_size": size }).to_string(),
        ))
        .unwrap()
}

fn complete_request(ip: ConnectInfo<SocketAddr>, token: &str, id: &str) -> Request<Body> {
    Request::builder()
        .method("POST")
        .uri(format!("/attachments/{id}/complete"))
        .header("content-type", "application/json")
        .header("authorization", format!("Bearer {token}"))
        .extension(ip)
        .body(Body::from("{}"))
        .unwrap()
}

async fn upload(url: &str, size: usize) {
    let response = reqwest::Client::new().put(url).body(vec![3u8; size]).send().await.unwrap();
    assert!(response.status().is_success(), "S3 upload failed: {}", response.status());
}

#[ignore = "mutates global server_policy; run explicitly against an isolated QA database"]
#[tokio::test]
async fn quota_init_complete_concurrency_expiry_and_backup_contribution() {
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token, user_id, _device_id) = register(&app, ip).await;

    // Small synthetic quota; restore in this single-test binary after the run.
    sqlx::query("UPDATE server_policy SET max_file_bytes=10000,user_quota_bytes=1000,storage_budget_bytes=0 WHERE id=true")
        .execute(&state.db)
        .await
        .unwrap();

    // Two concurrent 600-byte reservations must not both pass a 1000-byte quota.
    let (r1, r2) = tokio::join!(
        app.clone().oneshot(init_request(ip, &token, 600)),
        app.clone().oneshot(init_request(ip, &token, 600)),
    );
    let r1 = r1.unwrap();
    let r2 = r2.unwrap();
    let statuses = [r1.status(), r2.status()];
    assert_eq!(statuses.iter().filter(|s| **s == StatusCode::OK).count(), 1, "exactly one concurrent reservation must win: {statuses:?}");
    assert_eq!(statuses.iter().filter(|s| **s == StatusCode::BAD_REQUEST).count(), 1, "the other reservation must be quota-rejected: {statuses:?}");
    let winning_body = if r1.status() == StatusCode::OK { body_json(r1).await } else { body_json(r2).await };
    let id = winning_body["attachment_id"].as_str().unwrap().to_string();
    let url = winning_body["upload_url"].as_str().unwrap().to_string();
    upload(&url, 600).await;
    assert_eq!(app.clone().oneshot(complete_request(ip, &token, &id)).await.unwrap().status(), StatusCode::OK);

    // Completion must not double-count or free the already-reserved bytes.
    let third = app.clone().oneshot(init_request(ip, &token, 600)).await.unwrap();
    assert_eq!(third.status(), StatusCode::BAD_REQUEST, "600 reserved + 600 new > 1000");

    // Expire/cancel the orphan; the reservation must be released.
    sqlx::query("UPDATE attachments SET created_at=now()-interval '25 hours', upload_lease_until=now()-interval '1 minute' WHERE id=$1")
        .bind(Uuid::parse_str(&id).unwrap())
        .execute(&state.db)
        .await
        .unwrap();
    assert!(messenger_server::attachments::cleanup::delete_abandoned(&state, Uuid::parse_str(&id).unwrap(), None).await.unwrap());
    let after_cleanup = app.clone().oneshot(init_request(ip, &token, 600)).await.unwrap();
    assert_eq!(after_cleanup.status(), StatusCode::OK, "expired reservation must release quota");

    // History backup storage counts toward the same user quota.
    sqlx::query("INSERT INTO history_backup_storage(object_key,owner_id,bytes) VALUES($1,$2,500)")
        .bind(format!("history-media/quota-{}", Uuid::new_v4()))
        .bind(Uuid::parse_str(&user_id).unwrap())
        .execute(&state.db)
        .await
        .unwrap();
    let over_budget = app.clone().oneshot(init_request(ip, &token, 600)).await.unwrap();
    assert_eq!(over_budget.status(), StatusCode::BAD_REQUEST, "600 attachment + 500 backup > 1000");

    sqlx::query("UPDATE server_policy SET max_file_bytes=104861696,user_quota_bytes=0,storage_budget_bytes=0 WHERE id=true")
        .execute(&state.db)
        .await
        .unwrap();
}
