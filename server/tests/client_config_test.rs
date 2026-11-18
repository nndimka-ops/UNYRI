use std::net::SocketAddr;

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use serde_json::Value;
use tower::ServiceExt;

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
        identity: Default::default(),
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

fn get_request(uri: &str, ip: ConnectInfo<SocketAddr>) -> Request<Body> {
    Request::builder().method("GET").uri(uri).extension(ip).body(Body::empty()).unwrap()
}

async fn body_json(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
}

async fn fetch_config(app: &axum::Router) -> Value {
    let response = app.clone().oneshot(get_request("/client/config", connect_info())).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await
}

/// One test touches the shared `server_policy` row, so the steps stay in one
/// function and the original value is restored before any assertion.
#[tokio::test]
async fn client_config_is_public_cacheable_and_takes_the_higher_console_minimum() {
    let state = spawn_state().await;
    let db = state.db.clone();
    let app = messenger_server::app(state);

    let response = app.clone().oneshot(get_request("/client/config", connect_info())).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK, "no token is needed");
    assert_eq!(response.headers()["cache-control"], "public, max-age=60");
    let body = body_json(response).await;
    assert_eq!(body["schema_version"], 1);
    for platform in ["ios", "android", "desktop"] {
        let entry = &body["platforms"][platform];
        assert!(entry["minimum_version"].is_string(), "{platform}");
        assert!(entry["recommended_version"].is_string(), "{platform}");
        assert!(entry.get("update_url").is_some(), "{platform}");
    }

    let original: String = sqlx::query_scalar("SELECT min_ios_version FROM server_policy WHERE id=true")
        .fetch_one(&db).await.unwrap();
    sqlx::query("UPDATE server_policy SET min_ios_version='9.9.9' WHERE id=true").execute(&db).await.unwrap();
    let raised = fetch_config(&app).await;
    sqlx::query("UPDATE server_policy SET min_ios_version='' WHERE id=true").execute(&db).await.unwrap();
    let cleared = fetch_config(&app).await;
    sqlx::query("UPDATE server_policy SET min_ios_version=$1 WHERE id=true").bind(&original).execute(&db).await.unwrap();

    let ios = &raised["platforms"]["ios"];
    assert_eq!(ios["minimum_version"], "9.9.9");
    let recommended = ios["recommended_version"].as_str().unwrap();
    assert_ne!(
        messenger_server::client_config::compare_versions(recommended, "9.9.9"),
        std::cmp::Ordering::Less,
        "recommended never sits below the minimum"
    );
    let env_minimum = std::env::var("CLIENT_IOS_MINIMUM_VERSION").unwrap_or_default();
    if env_minimum.trim().is_empty() {
        assert_eq!(cleared["platforms"]["ios"]["minimum_version"], "0.0.0", "an empty console value is ignored");
    }
}
